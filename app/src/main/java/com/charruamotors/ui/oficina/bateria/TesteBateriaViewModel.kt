package com.charruamotors.ui.oficina.bateria

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.FaseBateriaSimulada
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.obd.sessao.VeiculoSessao
import com.charruamotors.oficina.bateria.EstadoTesteBateria
import com.charruamotors.oficina.bateria.EtapaBateria
import com.charruamotors.oficina.bateria.EventoTesteBateria
import com.charruamotors.oficina.bateria.LaudoBateria
import com.charruamotors.oficina.bateria.LeituraTensao
import com.charruamotors.oficina.bateria.OffsetAdaptador
import com.charruamotors.oficina.bateria.RegistroTesteBateria
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Tudo o que a tela do teste mostra. */
data class TesteBateriaUi(
    val teste: EstadoTesteBateria = EstadoTesteBateria(),
    /** Ajuste do adaptador deste veículo. */
    val offsetV: Double = 0.0,
    val historico: List<RegistroTesteBateria> = emptyList(),
    /** Veículo carregado e leitura pronta para capturar. */
    val pronta: Boolean = false,
    /** Laudo em texto, quando o teste termina. */
    val laudo: String? = null,
)

sealed interface EventoBateria {
    data class Mensagem(@param:StringRes val resId: Int) : EventoBateria
    data class EtapaConcluida(val etapa: EtapaBateria) : EventoBateria

    /** Sem conexão ao abrir, ou a conexão caiu: a tela avisa e fecha. */
    data class SemConexao(@param:StringRes val resId: Int) : EventoBateria
}

/**
 * Teste Guiado de Bateria, Partida e Alternador. A máquina de estados vive na sessão
 * ([SessaoObd.testeBateria]); aqui ficam o veículo (combustível, ajuste e histórico do perfil isolado)
 * e, nos modos 21/22, a amostragem do `ATRV` numa sessão manual — o polling da montadora fica pausado,
 * sem mudar a sequência dele, e volta ao fechar a tela.
 */
class TesteBateriaViewModel(
    private val sessao: SessaoObd,
    private val perfis: GerenciadorPerfisCarro,
    private val veiculoId: Long,
    private val buscarVeiculo: suspend (Long) -> Veiculo?,
    private val relogio: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {

    private val cliente = sessao.cliente
    private val controlador = sessao.testeBateria
    private val simulador = cliente as? ObdClientFake

    /** OBD2 padrão com RPM: polling prioritário. Senão (modos 21/22), só o `ATRV`. */
    private val comRpm = cliente.infoConexao.value.modo == ModoComunicacaoEcu.OBD2_PADRAO &&
        Pids.RPM in cliente.pidsSuportados.value

    private var faseSimulada: FaseBateriaSimulada? = null
    private var amostragem: Job? = null
    private var veiculoTexto: String? = null
    private var laudoCache: Pair<Any, String>? = null

    private val _ui = MutableStateFlow(TesteBateriaUi())
    val ui: StateFlow<TesteBateriaUi> = _ui.asStateFlow()

    private val _eventos = Channel<EventoBateria>(Channel.BUFFERED)
    val eventos: Flow<EventoBateria> = _eventos.receiveAsFlow()

    init {
        viewModelScope.launch { abrir() }
    }

    private suspend fun abrir() {
        if (cliente.estado.value !is EstadoConexaoObd.Conectado) {
            _eventos.send(EventoBateria.SemConexao(R.string.oficina_sem_conexao))
            return
        }
        val veiculo = if (veiculoId > 0) runCatching { buscarVeiculo(veiculoId) }.getOrNull() else null
        veiculoTexto = veiculo?.let { "${it.descricao} (${it.placaFormatada})" }
        val perfil = if (veiculoId > 0) perfis.ler(veiculoId) else null
        // Diesel pelo combustível do perfil do carro; o interruptor da tela corrige.
        val diesel = veiculo != null && perfil != null &&
            (perfil.resolver(veiculo).preset?.combustivel == TipoCombustivel.DIESEL ||
                VeiculoSessao.de(veiculo, perfil).combustivel == TipoCombustivel.DIESEL)
        // Reaberta com o mesmo carro, a escolha manual do motor vale.
        val atual = controlador.estado.value
        val retomando = atual.veiculoId == veiculoId && atual.resumo.temMedicao
        controlador.preparar(veiculoId, comRpm, if (retomando) atual.resumo.diesel else diesel, perfil?.offsetTensaoV ?: 0.0)

        viewModelScope.launch {
            controlador.eventos.collect { evento ->
                _eventos.send(
                    when (evento) {
                        is EventoTesteBateria.EtapaConcluida -> EventoBateria.EtapaConcluida(evento.etapa)
                        EventoTesteBateria.PartidaNaoDetectada -> EventoBateria.Mensagem(R.string.bateria_partida_nao_detectada)
                        EventoTesteBateria.MotorJaLigado -> EventoBateria.Mensagem(R.string.bateria_motor_ja_ligado)
                        EventoTesteBateria.CondicaoNaoAtendida -> EventoBateria.Mensagem(R.string.bateria_condicao_nao_atendida)
                        EventoTesteBateria.LeiturasInsuficientes -> EventoBateria.Mensagem(R.string.bateria_leituras_insuficientes)
                    },
                )
                sincronizarSimulador()
            }
        }
        viewModelScope.launch {
            controlador.estado.combine(perfis.alteracoes) { estado, _ -> estado }.collect(::publicar)
        }
        viewModelScope.launch {
            cliente.estado.collect { estado ->
                if (estado !is EstadoConexaoObd.Conectado) {
                    controlador.perderComunicacao()
                    _eventos.send(EventoBateria.SemConexao(R.string.oficina_conexao_perdida))
                }
            }
        }
        sincronizarSimulador()
        if (!comRpm) amostragem = viewModelScope.launch { amostrarAtrv() }
    }

    /** Modos 21/22: `ATRV` em alta taxa (o adaptador dita o ritmo). */
    private suspend fun amostrarAtrv() {
        if (cliente.iniciarSessaoManual().isFailure) {
            _eventos.send(EventoBateria.SemConexao(R.string.oficina_sem_conexao))
            return
        }
        while (viewModelScope.isActive && cliente.estado.value is EstadoConexaoObd.Conectado) {
            val inicio = relogio()
            if (controlador.estado.value.capturando) {
                val resposta = cliente.enviarComandoManual(Pids.TENSAO_BATERIA, TIMEOUT_ATRV_MS, registrar = false)
                controlador.registrar(
                    resposta.fold(
                        onSuccess = { LeituraTensao.deResposta(it.resposta, it.semPrompt) },
                        onFailure = { LeituraTensao.Perdida },
                    ),
                )
            }
            delay((PERIODO_MINIMO_MS - (relogio() - inicio)).coerceAtLeast(RESPIRO_MINIMO_MS))
        }
    }

    private fun publicar(estado: EstadoTesteBateria) {
        val perfil = if (veiculoId > 0) perfis.ler(veiculoId) else null
        _ui.update {
            TesteBateriaUi(
                teste = estado,
                offsetV = estado.resumo.offsetV,
                historico = perfil?.historicoBateria?.testes.orEmpty(),
                pronta = true,
                laudo = laudoDe(estado),
            )
        }
    }

    /** O laudo é gerado uma vez por resultado. */
    private fun laudoDe(estado: EstadoTesteBateria): String? {
        if (estado.etapa != EtapaBateria.CONCLUIDO || !estado.resumo.temMedicao) return null
        laudoCache?.takeIf { it.first == estado.resumo }?.let { return it.second }
        return LaudoBateria.gerar(estado.resumo, veiculoTexto, LocalDateTime.now()).also { laudoCache = estado.resumo to it }
    }

    // ---------------------------------------------------------------- ações da tela

    /** Botão principal: Medir / Iniciar captura da partida, ou Cancelar durante a captura. */
    fun alternarCaptura() {
        if (!_ui.value.pronta) return
        if (controlador.estado.value.capturando) {
            controlador.cancelar()
        } else if (!sessao.iniciarCapturaBateria()) {
            _eventos.trySend(EventoBateria.SemConexao(R.string.oficina_sem_conexao))
        }
        sincronizarSimulador()
    }

    fun pularEtapa() {
        controlador.pular()
        sincronizarSimulador()
    }

    fun reiniciar() {
        controlador.reiniciar()
        sincronizarSimulador()
    }

    fun definirDiesel(diesel: Boolean) {
        if (_ui.value.pronta && diesel != controlador.estado.value.resumo.diesel) controlador.definirDiesel(diesel)
    }

    /** Soma [passos] de 0,1 V ao ajuste deste veículo; com medições feitas, o teste recomeça. */
    fun ajustarOffset(passos: Int) {
        if (!_ui.value.pronta || controlador.estado.value.capturando) return
        val tinhaMedicao = controlador.estado.value.resumo.temMedicao
        val novo = OffsetAdaptador.limitar(_ui.value.offsetV + passos * OffsetAdaptador.PASSO)
        if (!controlador.definirOffset(novo)) return
        if (veiculoId > 0) perfis.atualizar(veiculoId) { it.copy(offsetTensaoV = novo) }
        if (tinhaMedicao) _eventos.trySend(EventoBateria.Mensagem(R.string.bateria_offset_reiniciou))
        sincronizarSimulador()
    }

    /**
     * Modo simulado: a fase da curva segue o roteiro — repouso até a captura da partida começar, o
     * roteiro da partida durante ela e o alternador depois que o motor "pegou".
     */
    private fun sincronizarSimulador() {
        val simular = simulador ?: return
        val estado = controlador.estado.value
        val fase = when (estado.etapa) {
            EtapaBateria.REPOUSO -> FaseBateriaSimulada.REPOUSO
            EtapaBateria.PARTIDA -> if (estado.capturando) FaseBateriaSimulada.PARTIDA else FaseBateriaSimulada.REPOUSO
            EtapaBateria.ALTERNADOR_LENTA, EtapaBateria.CONCLUIDO -> FaseBateriaSimulada.MARCHA_LENTA
            EtapaBateria.ALTERNADOR_CARGA -> FaseBateriaSimulada.CARGA
        }
        // A partida sempre recomeça o roteiro; as demais fases só mudam quando trocam.
        if (fase != faseSimulada || fase == FaseBateriaSimulada.PARTIDA) {
            faseSimulada = fase
            simular.simularBateria(fase)
        }
    }

    /**
     * Tela fechada de vez: cancela a captura (o polling volta ao normal) e encerra a sessão manual. O
     * `viewModelScope` já está cancelado aqui, então usa um escopo próprio; encerrar é idempotente.
     */
    override fun onCleared() {
        sairDaTela()
        amostragem?.cancel()
        simulador?.simularBateria(null)
        if (!comRpm) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { cliente.encerrarSessaoManual() }
    }

    /** A tela deixou de estar visível: a captura em curso é cancelada. */
    fun sairDaTela() = controlador.cancelar()

    companion object {
        /** Piso entre amostras do `ATRV`: ~20/s. */
        const val PERIODO_MINIMO_MS = 50L
        private const val RESPIRO_MINIMO_MS = 5L

        /** Prazo curto: na partida uma amostra perdida não pode segurar as seguintes. */
        const val TIMEOUT_ATRV_MS = 1_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                // O carro da sessão em curso manda; sem ele, o escolhido na Oficina.
                val daSessao = app.sessaoObd.estadoSessao.value.veiculoId
                TesteBateriaViewModel(
                    sessao = app.sessaoObd,
                    perfis = app.perfisCarro,
                    veiculoId = daSessao.takeIf { it > 0 } ?: veiculoId,
                    buscarVeiculo = { id -> app.veiculoRepository.buscarPorId(id) },
                )
            }
        }
    }
}
