package com.charruamotors.obd.sessao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.direcao.hud.HudFlutuanteService
import com.charruamotors.obd.ModoObd
import com.charruamotors.ui.util.PermissoesBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano (`connectedDevice`) da sessão OBD2 — o "modo caixa-preta". Não abre
 * conexão própria: segura o processo vivo enquanto a [SessaoObd] do Application estiver ativa
 * (conexão manual pela aba OBD2 ou automática pelo Bluetooth do carro), com uma notificação
 * discreta (canal de baixa prioridade, sem som nem vibração) mostrando o status
 * (`Conectado · Toyota SW4 · Gravando log`) e a ação **Desconectar**. Para sozinho quando a sessão
 * termina — os logs já foram fechados e salvos pela sessão.
 *
 * v2.12.0: com "Gravar posição GPS no log" (sessão real e permissão concedida) o serviço também
 * declara o tipo `location`, para o GPS continuar chegando com o app minimizado. Se o Android
 * recusar (sessão automática iniciada em segundo plano), segue só como `connectedDevice`: a conexão
 * OBD2 nunca depende da localização.
 */
class ObdSessaoService : Service() {

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessao: SessaoObd get() = (application as CharruaMotorsApp).sessaoObd

    private var emPrimeiroPlano = false
    private var observando = false
    private var ultimoTexto: String? = null

    /** O primeiro plano em vigor inclui o tipo `location`? */
    private var comLocalizacao = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_SILENCIAR) {
            sessao.silenciarAlertas()
            if (!emPrimeiroPlano) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACAO_DESCONECTAR) {
            sessao.desconectar()
            if (!emPrimeiroPlano) stopSelf()
            return START_NOT_STICKY
        }
        val automatica = intent?.action == ACAO_AUTOMATICA
        // startForeground antes de tudo: um serviço iniciado com startForegroundService que não entra
        // em primeiro plano a tempo derruba o app.
        if (!emPrimeiroPlano) {
            emPrimeiroPlano = entrarEmPrimeiroPlano()
            if (!emPrimeiroPlano) {
                if (automatica) notificarBloqueio(this)
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            // Novo pedido com o serviço já em primeiro plano (ex.: o app voltou à frente).
            ajustarTipos()
        }
        observar()
        if (automatica) {
            cancelarAlertaBloqueio(this)
            val gatilho = intent?.getStringExtra(EXTRA_GATILHO)
            escopo.launch {
                val iniciou = sessao.iniciarAutomatico(gatilho)
                if (!iniciou && !sessao.estadoSessao.value.ativa) encerrarServico()
            }
        } else if (!sessao.estadoSessao.value.ativa) {
            encerrarServico()
        }
        // Sem recriação automática: se o sistema matar o processo, a conexão também se foi.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        escopo.cancel()
        super.onDestroy()
    }

    /** Acompanha a sessão: atualiza a notificação e para o serviço quando ela termina. */
    private fun observar() {
        if (observando) return
        observando = true
        escopo.launch {
            (application as CharruaMotorsApp).preferenciasApp.config
                .map { it.gravarPosicaoGps }
                .distinctUntilChanged()
                .collect { ajustarTipos() }
        }
        escopo.launch {
            var viuAtiva = false
            // Os alertas entram na mesma coleta: a notificação mostra o aviso com a tela bloqueada.
            sessao.estadoSessao.combine(sessao.alertas) { estado, _ -> estado }.collect { estado ->
                if (estado.ativa) {
                    viuAtiva = true
                    atualizarNotificacao(estado)
                } else if (viuAtiva) {
                    encerrarServico()
                }
            }
        }
    }

    private fun encerrarServico() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        emPrimeiroPlano = false
        stopSelf()
    }

    // ------------------------------------------------------------------ notificação

    private fun entrarEmPrimeiroPlano(): Boolean {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (querLocalizacao() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            iniciarPrimeiroPlano(base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        ) {
            comLocalizacao = true
            return true
        }
        comLocalizacao = false
        return iniciarPrimeiroPlano(base)
    }

    private fun iniciarPrimeiroPlano(tipos: Int): Boolean = try {
        ServiceCompat.startForeground(this, ID_NOTIFICACAO, criarNotificacao(sessao.estadoSessao.value), tipos)
        true
    } catch (e: RuntimeException) {
        // Android 12+: ForegroundServiceStartNotAllowedException (início em segundo plano bloqueado);
        // Android 14+: SecurityException sem a permissão exigida pelo tipo (Bluetooth ou localização).
        Log.w(TAG, "Não foi possível iniciar a sessão OBD em primeiro plano (tipos $tipos)", e)
        false
    }

    private fun querLocalizacao(): Boolean {
        val app = application as CharruaMotorsApp
        return app.preferenciasApp.config.value.gravarPosicaoGps &&
            sessao.modo.value != ModoObd.SIMULADO &&
            app.velocidadeGps.temPermissao()
    }

    /** A opção, a permissão ou o modo mudaram: troca os tipos do primeiro plano, sem derrubar o serviço. */
    private fun ajustarTipos() {
        if (emPrimeiroPlano && querLocalizacao() != comLocalizacao) entrarEmPrimeiroPlano()
    }

    private fun atualizarNotificacao(estado: EstadoSessao) {
        val alertas = sessao.alertas.value
        val chave = "${textoStatus(estado)}|${alertas.texto}|${alertas.podeSilenciar}"
        if (chave == ultimoTexto) return
        ultimoTexto = chave
        runCatching { getSystemService(NotificationManager::class.java)?.notify(ID_NOTIFICACAO, criarNotificacao(estado)) }
    }

    private fun textoStatus(estado: EstadoSessao): String =
        if (estado.ativa) SessaoObd.descreverStatus(estado) else getString(R.string.sessao_notificacao_preparando)

    private fun criarNotificacao(estado: EstadoSessao): Notification {
        criarCanais(this)
        val desconectar = PendingIntent.getService(
            this,
            REQ_DESCONECTAR,
            Intent(this, ObdSessaoService::class.java).setAction(ACAO_DESCONECTAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val abrirApp = HudFlutuanteService.intentVoltarAoApp(this)?.let {
            PendingIntent.getActivity(this, REQ_ABRIR, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val alertas = sessao.alertas.value
        val alerta = alertas.principal
        val silenciar = PendingIntent.getService(
            this,
            REQ_SILENCIAR,
            Intent(this, ObdSessaoService::class.java).setAction(ACAO_SILENCIAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_caixa_preta)
            .setContentTitle(alerta?.texto ?: getString(R.string.sessao_notificacao_titulo))
            .setContentText(textoStatus(estado))
            .apply {
                if (alerta != null) {
                    setColor(ContextCompat.getColor(this@ObdSessaoService, R.color.status_pendente))
                    setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    if (alertas.podeSilenciar) addAction(R.drawable.ic_fechar, getString(R.string.alerta_silenciar), silenciar)
                }
            }
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(abrirApp)
            .addAction(R.drawable.ic_fechar, getString(R.string.sessao_desconectar), desconectar)
            .build()
    }

    companion object {
        private const val TAG = "ObdSessaoService"
        private const val CANAL = "sessao_obd"
        private const val CANAL_ALERTA = "sessao_obd_alerta"
        private const val ID_NOTIFICACAO = 1201
        private const val ID_ALERTA = 1202
        private const val REQ_DESCONECTAR = 1
        private const val REQ_ABRIR = 2
        private const val REQ_INICIAR = 3
        private const val REQ_SILENCIAR = 4
        const val ACAO_SILENCIAR = "com.charruamotors.sessao.SILENCIAR"
        const val ACAO_AUTOMATICA = "com.charruamotors.sessao.AUTOMATICA"
        const val ACAO_DESCONECTAR = "com.charruamotors.sessao.DESCONECTAR"
        const val EXTRA_GATILHO = "gatilho"

        /** Conexão manual (app em primeiro plano): sobe a notificação da sessão. */
        fun iniciar(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ObdSessaoService::class.java))
            } catch (e: IllegalStateException) {
                // A sessão segue no processo; só fica sem a proteção do primeiro plano.
                Log.w(TAG, "Serviço da sessão não iniciado", e)
            }
        }

        /**
         * Com o app em primeiro plano: pede ao serviço da sessão real em curso que inclua o tipo
         * `location` (a sessão automática nasce em segundo plano, onde o Android não o concede).
         */
        fun revalidarLocalizacao(context: Context) {
            val app = context.applicationContext as CharruaMotorsApp
            if (!app.preferenciasApp.config.value.gravarPosicaoGps) return
            if (!app.sessaoObd.estadoSessao.value.ativa || app.sessaoObd.modo.value == ModoObd.SIMULADO) return
            if (PermissoesBluetooth.concedidas(context)) iniciar(context)
        }

        /**
         * Bluetooth do carro conectou (broadcast ACL, que libera o início em segundo plano). Se o
         * Android ainda assim bloquear (`ForegroundServiceStartNotAllowedException`, bateria
         * otimizada no One UI...), avisa com uma notificação: um toque inicia a sessão.
         */
        fun iniciarAutomatico(context: Context, gatilho: String?) {
            val intent = Intent(context, ObdSessaoService::class.java).setAction(ACAO_AUTOMATICA).putExtra(EXTRA_GATILHO, gatilho)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException é uma IllegalStateException
                Log.w(TAG, "Início automático bloqueado pelo sistema", e)
                notificarBloqueio(context)
            }
        }

        private fun criarCanais(context: Context) {
            val gerenciador = context.getSystemService(NotificationManager::class.java) ?: return
            if (gerenciador.getNotificationChannel(CANAL) == null) {
                gerenciador.createNotificationChannel(
                    NotificationChannel(CANAL, context.getString(R.string.sessao_canal_nome), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.sessao_canal_descricao)
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
                )
            }
            if (gerenciador.getNotificationChannel(CANAL_ALERTA) == null) {
                gerenciador.createNotificationChannel(
                    NotificationChannel(CANAL_ALERTA, context.getString(R.string.sessao_canal_alerta_nome), NotificationManager.IMPORTANCE_HIGH).apply {
                        description = context.getString(R.string.sessao_canal_alerta_descricao)
                    },
                )
            }
        }

        /** Fallback seguro: o sistema bloqueou o serviço vindo do segundo plano; o toque do usuário libera. */
        fun notificarBloqueio(context: Context) {
            criarCanais(context)
            val iniciar = PendingIntent.getForegroundService(
                context,
                REQ_INICIAR,
                Intent(context, ObdSessaoService::class.java).setAction(ACAO_AUTOMATICA),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val texto = context.getString(R.string.sessao_bloqueada_texto)
            val notificacao = NotificationCompat.Builder(context, CANAL_ALERTA)
                .setSmallIcon(R.drawable.ic_caixa_preta)
                .setContentTitle(context.getString(R.string.sessao_bloqueada_titulo))
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(iniciar)
                .addAction(R.drawable.ic_caixa_preta, context.getString(R.string.sessao_bloqueada_acao), iniciar)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID_ALERTA, notificacao) }
        }

        private fun cancelarAlertaBloqueio(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID_ALERTA) }
        }
    }
}
