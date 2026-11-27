package com.charruamotors.ui.oficina.bateria

import com.charruamotors.obd.sessao.FerramentaManualObd
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ActivityTesteBateriaBinding
import com.charruamotors.oficina.bateria.AvisoCaptura
import com.charruamotors.oficina.bateria.EstadoTesteBateria
import com.charruamotors.oficina.bateria.EtapaBateria
import com.charruamotors.oficina.bateria.LaudoBateria
import com.charruamotors.oficina.bateria.OffsetAdaptador
import com.charruamotors.oficina.bateria.PontoCurva
import com.charruamotors.oficina.bateria.ReferenciasBateria
import com.charruamotors.oficina.bateria.RegistroTesteBateria
import com.charruamotors.oficina.bateria.TipoReferenciaBateria
import com.charruamotors.oficina.bateria.ResultadoCarga
import com.charruamotors.oficina.bateria.Veredito
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.ui.componentes.GraficoSeriesView
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.TextoCompartilhavel
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Teste Guiado de Bateria, Partida e Alternador em 3 etapas (repouso, partida, alternador em marcha
 * lenta e com carga), com a curva de tensão, o veredito e o histórico do carro. A tela fica acesa
 * durante o teste (o motorista está fora do carro olhando o celular).
 */
class TesteBateriaActivity : AppCompatActivity(), FerramentaManualObd {

    private lateinit var binding: ActivityTesteBateriaBinding
    private val viewModel: TesteBateriaViewModel by viewModels { TesteBateriaViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityTesteBateriaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.graficoTensao.formatarValor = { _, v -> v.comoNumero(1) }

        binding.botaoAcao.setOnClickListener { viewModel.alternarCaptura() }
        binding.botaoPular.setOnClickListener { viewModel.pularEtapa() }
        binding.botaoRefazer.setOnClickListener { viewModel.reiniciar() }
        binding.botaoOffsetMenos.setOnClickListener { viewModel.ajustarOffset(-1) }
        binding.botaoOffsetMais.setOnClickListener { viewModel.ajustarOffset(1) }
        // Clique (e não o ouvinte de mudança): o estado desenhado pela tela não volta como escolha.
        binding.switchDiesel.setOnClickListener { viewModel.definirDiesel(binding.switchDiesel.isChecked) }
        binding.botaoCopiarLaudo.setOnClickListener {
            viewModel.ui.value.laudo?.let { TextoCompartilhavel.copiar(this, getString(R.string.bateria_laudo_assunto), it) }
        }
        binding.botaoCompartilharLaudo.setOnClickListener {
            viewModel.ui.value.laudo?.let { TextoCompartilhavel.compartilhar(this, getString(R.string.bateria_laudo_assunto), it) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.ui.collect(::mostrar) }
                launch {
                    viewModel.eventos.collect { evento ->
                        when (evento) {
                            is EventoBateria.Mensagem -> mensagem(getString(evento.resId))
                            is EventoBateria.EtapaConcluida -> mensagem(getString(R.string.bateria_etapa_ok, tituloEtapa(evento.etapa)))
                            is EventoBateria.SemConexao -> avisarSemConexao(evento.resId)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        // Saiu da tela (não é giro): o polling volta ao normal.
        if (!isChangingConfigurations) viewModel.sairDaTela()
        super.onStop()
    }

    private fun mostrar(ui: TesteBateriaUi) = with(binding) {
        val teste = ui.teste
        val etapa = teste.etapa
        textoEtapa.definirTexto(
            if (etapa == EtapaBateria.CONCLUIDO) getString(R.string.bateria_etapa_concluida) else getString(R.string.bateria_etapa_n, etapa.numero),
        )
        tituloEtapa.definirTexto(tituloEtapa(etapa))
        instrucaoEtapa.definirTexto(getString(instrucao(etapa)))
        textoTensao.definirTexto(teste.tensao?.let { getString(R.string.bateria_tensao_ao_vivo, it.comoNumero(1)) } ?: getString(R.string.valor_indisponivel))
        textoRpm.definirTexto(
            when {
                !teste.comRpm -> getString(R.string.bateria_sem_rpm)
                teste.rpm == null -> getString(R.string.bateria_rpm_indisponivel)
                else -> getString(R.string.bateria_rpm, teste.rpm.comoNumero(0))
            },
        )
        mostrarCurva(teste.curva)
        mostrarReferencias(teste)

        progressoCaptura.isInvisible = !teste.capturando
        val progresso = teste.progresso
        if (teste.capturando) {
            if (progresso == null) {
                if (!progressoCaptura.isIndeterminate) {
                    progressoCaptura.isVisible = false
                    progressoCaptura.isIndeterminate = true
                    progressoCaptura.isVisible = true
                }
            } else {
                progressoCaptura.isIndeterminate = false
                progressoCaptura.setProgressCompat((progresso * 100).toInt(), false)
            }
        }
        val status = status(teste)
        textoStatus.isVisible = status != null
        textoStatus.definirTexto(status)

        grupoAcoes.isVisible = etapa != EtapaBateria.CONCLUIDO
        botaoAcao.isEnabled = ui.pronta
        botaoAcao.definirTexto(getString(when {
            teste.capturando -> R.string.bateria_cancelar
            etapa == EtapaBateria.PARTIDA -> R.string.bateria_iniciar_partida
            else -> R.string.bateria_medir
        }))
        botaoPular.isEnabled = ui.pronta && !teste.capturando

        switchDiesel.isChecked = teste.resumo.diesel
        switchDiesel.isEnabled = ui.pronta
        textoOffset.definirTexto(getString(R.string.bateria_offset_valor, LaudoBateria.offset(ui.offsetV)))
        botaoOffsetMenos.isEnabled = ui.pronta && !teste.capturando && ui.offsetV > OffsetAdaptador.MINIMO
        botaoOffsetMais.isEnabled = ui.pronta && !teste.capturando && ui.offsetV < OffsetAdaptador.MAXIMO

        mostrarResultados(teste)
        cardLaudo.isVisible = ui.laudo != null
        if (ui.laudo != null) {
            val r = teste.resumo
            val bateria = LaudoBateria.vereditoBateria(r)
            val alternador = LaudoBateria.vereditoAlternador(r)
            colorir(vereditoBateria, getString(R.string.bateria_veredito_bateria, bateria.titulo), bateria.gravidade)
            colorir(vereditoAlternador, getString(R.string.bateria_veredito_alternador, alternador.titulo), alternador.gravidade)
            colorir(
                vereditoRepouso, getString(R.string.bateria_veredito_repouso, LaudoBateria.linhaRepouso(r.repouso)),
                r.repouso?.veredito?.gravidade,
            )
            colorir(
                vereditoPartida, getString(R.string.bateria_veredito_partida, LaudoBateria.linhaPartida(r.partida)),
                r.partida?.veredito?.gravidade,
            )
            textoLaudo.definirTexto(ui.laudo)
        }

        textoHistorico.definirTexto(
            if (ui.historico.isEmpty()) {
                getString(R.string.bateria_historico_vazio)
            } else {
                ui.historico.take(LINHAS_HISTORICO).joinToString("\n", transform = ::linhaHistorico)
            },
        )
    }

    private var ultimoPontoCurva: PontoCurva? = null

    private fun mostrarCurva(curva: List<PontoCurva>) {
        if (curva.lastOrNull() == ultimoPontoCurva) return
        ultimoPontoCurva = curva.lastOrNull()
        val instantes = LongArray(curva.size) { curva[it].instanteMs }
        val valores = DoubleArray(curva.size) { curva[it].volts ?: Double.NaN }
        val fim = instantes.lastOrNull() ?: 0L
        val inicio = instantes.firstOrNull() ?: 0L
        binding.graficoTensao.mostrarAoVivo(
            instantes,
            listOf(GraficoSeriesView.Serie(getString(R.string.bateria_serie_tensao), valores, getColor(R.color.veredito_bom))),
            JanelaReplay(inicio, maxOf(fim, inicio + JANELA_MINIMA_MS)),
        )
    }

    /** Limiar da partida (conforme diesel/Otto), faixa do alternador e o marcador do vale capturado. */
    private fun mostrarReferencias(teste: EstadoTesteBateria) {
        binding.graficoTensao.referencias = ReferenciasBateria.de(teste.resumo.diesel).map {
            val partida = it.tipo == TipoReferenciaBateria.PARTIDA_MINIMA
            val texto = when (it.tipo) {
                TipoReferenciaBateria.PARTIDA_MINIMA -> R.string.bateria_ref_partida
                TipoReferenciaBateria.ALTERNADOR_MINIMO -> R.string.bateria_ref_alternador_min
                TipoReferenciaBateria.ALTERNADOR_MAXIMO -> R.string.bateria_ref_alternador_max
            }
            val cor = getColor(if (partida) R.color.veredito_atencao else R.color.texto_secundario)
            GraficoSeriesView.Referencia(it.volts, getString(texto, LaudoBateria.v(it.volts)), cor)
        }
        binding.graficoTensao.marcador = teste.pontoVale?.let { ponto ->
            ponto.volts?.let { volts ->
                val rotulo = getString(R.string.bateria_ref_vale, LaudoBateria.v(volts))
                GraficoSeriesView.Marcador(ponto.instanteMs, volts, rotulo, getColor(R.color.veredito_ruim))
            }
        }
    }

    private fun status(teste: EstadoTesteBateria): String? {
        if (!teste.capturando) return null
        val base = when {
            teste.aviso != null -> getString(
                when (teste.aviso) {
                    AvisoCaptura.DESLIGUE_O_MOTOR -> R.string.bateria_aviso_desligue
                    AvisoCaptura.LIGUE_O_MOTOR -> R.string.bateria_aviso_ligue
                    AvisoCaptura.VOLTE_A_LENTA -> R.string.bateria_aviso_lenta
                    AvisoCaptura.ACELERE_A_2000 -> R.string.bateria_aviso_acelere
                },
            )
            teste.etapa == EtapaBateria.PARTIDA && teste.quedaDetectada ->
                getString(R.string.bateria_partida_em_curso, teste.vale?.let(LaudoBateria::v) ?: "—")
            teste.etapa == EtapaBateria.PARTIDA -> getString(R.string.bateria_aguardando_partida)
            else -> getString(R.string.bateria_medindo)
        }
        return if (teste.lvResets > 0) base + "\n" + getString(R.string.bateria_lv_resets, teste.lvResets) else base
    }

    private fun mostrarResultados(teste: EstadoTesteBateria) = with(binding) {
        val resumo = teste.resumo
        val r = resumo.repouso
        linha(resRepouso, r?.let { getString(R.string.bateria_res_repouso, LaudoBateria.linhaRepouso(it)) },
            r?.veredito, EtapaBateria.REPOUSO in resumo.puladas, R.string.bateria_res_repouso_pendente, R.string.bateria_etapa1_titulo)
        val p = resumo.partida
        linha(resPartida, p?.let { getString(R.string.bateria_res_partida, LaudoBateria.linhaPartida(it)) },
            p?.veredito, EtapaBateria.PARTIDA in resumo.puladas, R.string.bateria_res_partida_pendente, R.string.bateria_etapa2_titulo)
        linha(resLenta, resumo.lenta?.let { textoCarga(R.string.bateria_res_lenta, it) }, resumo.lenta?.veredito,
            EtapaBateria.ALTERNADOR_LENTA in resumo.puladas, R.string.bateria_res_lenta_pendente, R.string.bateria_etapa3a_titulo)
        linha(resCarga, resumo.carga?.let { textoCarga(R.string.bateria_res_carga, it) }, resumo.carga?.veredito,
            EtapaBateria.ALTERNADOR_CARGA in resumo.puladas, R.string.bateria_res_carga_pendente, R.string.bateria_etapa3b_titulo)
        avisoMotorLigado.isVisible = r?.motorPareceLigado == true
    }

    private fun textoCarga(formato: Int, c: ResultadoCarga) =
        getString(formato, LaudoBateria.v(c.media), LaudoBateria.v(c.pico), LaudoBateria.v(c.ripple), LaudoBateria.rotulo(c.veredito))

    private fun linha(view: TextView, texto: String?, veredito: Veredito?, pulada: Boolean, pendente: Int, titulo: Int) {
        view.definirTexto(texto ?: if (pulada) getString(R.string.bateria_res_pulada, getString(titulo)) else getString(pendente))
        view.setTextColor(veredito?.let { corDaGravidade(it.gravidade) } ?: getColor(R.color.texto_secundario))
    }

    private fun linhaHistorico(t: RegistroTesteBateria): String {
        val quando = FORMATO_HISTORICO.format(Instant.ofEpochMilli(t.quandoMs).atZone(ZoneId.systemDefault()))
        fun v(volts: Double?) = volts?.let(LaudoBateria::v) ?: "—"
        return getString(R.string.bateria_historico_linha, quando, v(t.repousoV), v(t.partidaMinV), v(t.cargaV ?: t.lentaV))
    }

    private fun colorir(view: TextView, texto: String, gravidade: Int?) {
        view.definirTexto(texto)
        view.setTextColor(gravidade?.let(::corDaGravidade) ?: getColor(R.color.texto_secundario))
    }

    private fun corDaGravidade(gravidade: Int): Int = ContextCompat.getColor(
        this,
        when (gravidade) {
            0 -> R.color.veredito_bom
            1 -> R.color.veredito_atencao
            else -> R.color.veredito_ruim
        },
    )

    private fun tituloEtapa(etapa: EtapaBateria): String = getString(
        when (etapa) {
            EtapaBateria.REPOUSO -> R.string.bateria_etapa1_titulo
            EtapaBateria.PARTIDA -> R.string.bateria_etapa2_titulo
            EtapaBateria.ALTERNADOR_LENTA -> R.string.bateria_etapa3a_titulo
            EtapaBateria.ALTERNADOR_CARGA -> R.string.bateria_etapa3b_titulo
            EtapaBateria.CONCLUIDO -> R.string.bateria_laudo_titulo
        },
    )

    private fun instrucao(etapa: EtapaBateria): Int = when (etapa) {
        EtapaBateria.REPOUSO -> R.string.bateria_etapa1_instrucao
        EtapaBateria.PARTIDA -> R.string.bateria_etapa2_instrucao
        EtapaBateria.ALTERNADOR_LENTA -> R.string.bateria_etapa3a_instrucao
        EtapaBateria.ALTERNADOR_CARGA -> R.string.bateria_etapa3b_instrucao
        EtapaBateria.CONCLUIDO -> R.string.bateria_concluido_instrucao
    }

    private var avisouSemConexao = false

    private fun avisarSemConexao(resId: Int) {
        if (isFinishing || avisouSemConexao) return
        avisouSemConexao = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bateria_titulo)
            .setMessage(resId)
            .setCancelable(false)
            .setPositiveButton(R.string.botao_fechar) { _, _ -> finish() }
            .show()
    }

    private fun mensagem(texto: String) {
        Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()
    }

    companion object {
        private const val JANELA_MINIMA_MS = 10_000L
        private const val LINHAS_HISTORICO = 8
        private val FORMATO_HISTORICO = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")

        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, TesteBateriaActivity::class.java).putExtra(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId)
    }
}
