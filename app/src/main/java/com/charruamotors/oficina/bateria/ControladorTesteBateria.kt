package com.charruamotors.oficina.bateria

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.PrioridadePolling
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tudo o que a tela do teste mostra. */
data class EstadoTesteBateria(
    val veiculoId: Long = 0,
    val etapa: EtapaBateria = EtapaBateria.REPOUSO,
    val capturando: Boolean = false,
    /** 0–1; null = indeterminado (esperando a partida) ou sem captura. */
    val progresso: Float? = null,
    /** Última tensão válida, já com o ajuste. */
    val tensao: Double? = null,
    val rpm: Double? = null,
    val lvResets: Int = 0,
    val quedaDetectada: Boolean = false,
    val vale: Double? = null,
    val aviso: AvisoCaptura? = null,
    val comRpm: Boolean = true,
    val resumo: ResumoTesteBateria = ResumoTesteBateria(),
    val curva: List<PontoCurva> = emptyList(),
)

/** O teste concluído nesta sessão (para o laudo PDF). */
data class TesteDaSessao(val veiculoId: Long, val resumo: ResumoTesteBateria)

/**
 * Liga a [TesteBateria] à sessão OBD. No OBD2 padrão, enquanto uma etapa captura, pede a
 * [PrioridadePolling] de RPM e tensão; concluída, cancelada ou com a tela fechada, retira. Toda
 * mudança passa por [publicar], o único ponto que decide a prioridade. Nos modos 21/22 não há
 * prioridade: a tela entrega o `ATRV` por [registrar] numa sessão manual.
 *
 * @param reiniciosDoAdaptador contador de `LV RESET` do cliente (a telemetria não os entrega como valor).
 * @param aoConcluir chamado uma vez por teste concluído com alguma medição, fora de qualquer trava.
 */
class ControladorTesteBateria(
    private val aplicarPrioridade: (PrioridadePolling?) -> Unit,
    private val relogioMs: () -> Long,
    private val suportados: () -> Set<String> = { emptySet() },
    private val vigiados: () -> Collection<String> = { emptyList() },
    private val reiniciosDoAdaptador: () -> Int = { 0 },
    private val aoConcluir: (TesteDaSessao) -> Unit = {},
) {
    private val trava = Any()
    private val teste = TesteBateria()
    private var veiculoId = 0L
    private var ultimaTensao: Double? = null
    private var ultimoRpm: Double? = null
    private var reiniciosVistos = 0
    private var aplicada: PrioridadePolling? = null

    private val _estado = MutableStateFlow(EstadoTesteBateria())
    val estado: StateFlow<EstadoTesteBateria> = _estado.asStateFlow()

    private val _eventos = MutableSharedFlow<EventoTesteBateria>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val eventos: SharedFlow<EventoTesteBateria> = _eventos.asSharedFlow()

    /** Último teste concluído desde o início da sessão; null = nenhum. */
    @Volatile
    var resultadoDaSessao: TesteDaSessao? = null
        private set

    /**
     * A tela abriu para [veiculo]: outro carro, ou o outro modo de leitura, começa do zero. Com o mesmo
     * carro o teste em andamento é retomado (ex.: partida inconclusiva por queda da conexão).
     */
    fun preparar(veiculo: Long, comRpm: Boolean, diesel: Boolean, offsetV: Double) = mudar {
        val novoOffset = OffsetAdaptador.limitar(offsetV)
        if (veiculo != veiculoId || comRpm != teste.comRpm || novoOffset != teste.offsetV) {
            teste.reiniciar()
            limparLeituras()
        }
        veiculoId = veiculo
        teste.comRpm = comRpm
        teste.offsetV = novoOffset
        teste.diesel = diesel
        null
    }

    /** Sessão OBD nova: o resultado anterior sai do laudo; um teste de outro carro ou já concluído zera. */
    fun novaSessao(veiculo: Long) = mudar {
        resultadoDaSessao = null
        if (veiculo != veiculoId || teste.concluido) {
            teste.reiniciar()
            limparLeituras()
        } else {
            teste.cancelarCaptura()
        }
        null
    }

    fun definirDiesel(diesel: Boolean) = mudar {
        teste.diesel = diesel
        resultadoDaSessao?.takeIf { teste.concluido }?.let { resultadoDaSessao = it.copy(resumo = teste.resumo()) }
        null
    }

    /** Devolve true se o ajuste mudou (e o teste recomeçou). */
    fun definirOffset(offsetV: Double): Boolean {
        val novo = OffsetAdaptador.limitar(offsetV)
        var mudou = false
        mudar {
            if (novo != teste.offsetV) {
                mudou = true
                teste.reiniciar()
                limparLeituras()
                teste.offsetV = novo
            }
            null
        }
        return mudou
    }

    fun iniciarCaptura() = mudar {
        if (!teste.concluido && !teste.capturando) {
            reiniciosVistos = reiniciosDoAdaptador()
            teste.iniciarCaptura(relogioMs())
        }
        null
    }

    /** Botão cancelar e saída da tela: para a captura (os resultados já medidos ficam). */
    fun cancelar() = mudar {
        teste.cancelarCaptura()
        null
    }

    fun pular() = mudar {
        val estavaConcluido = teste.concluido
        teste.pular()
        if (!estavaConcluido && teste.concluido) EventoTesteBateria.EtapaConcluida(EtapaBateria.ALTERNADOR_CARGA) else null
    }

    fun reiniciar() = mudar {
        teste.reiniciar()
        limparLeituras()
        null
    }

    /** Um instantâneo da telemetria: só vale o que foi lido **neste** ciclo. */
    fun amostrar(dados: DadosObd) = mudar {
        if (!teste.capturando || !teste.comRpm) return@mudar null
        fun fresco(chave: String) = if (dados.frescos?.contains(chave) == false) null else dados[chave]
        val agora = relogioMs()
        val rpm = fresco(Pids.RPM)
        val volts = fresco(Pids.TENSAO_BATERIA) ?: fresco(TENSAO_MODULO)
        var evento: EventoTesteBateria? = null
        val reinicios = reiniciosDoAdaptador()
        if (reinicios != reiniciosVistos) {
            reiniciosVistos = reinicios
            evento = teste.registrar(LeituraTensao.LvReset, agora)
        }
        // Um ciclo sem a tensão nem o RPM só avança o relógio; sem tensão, conta como amostra perdida.
        val leitura = volts?.let(LeituraTensao::Valida) ?: LeituraTensao.Perdida
        anotar(volts, rpm)
        evento ?: teste.registrar(leitura, agora, rpm)
    }

    /** Modos 21/22: uma leitura do `ATRV` da sessão manual. */
    fun registrar(leitura: LeituraTensao) = mudar {
        anotar((leitura as? LeituraTensao.Valida)?.volts, null)
        teste.registrar(leitura, relogioMs())
    }

    fun tique() {
        if (!_estado.value.capturando) return
        mudar { teste.tique(relogioMs()) }
    }

    fun perderComunicacao() = mudar { teste.perderComunicacao(relogioMs()) }

    private fun anotar(volts: Double?, rpm: Double?) {
        if (volts != null && volts >= TesteBateria.TENSAO_MINIMA_VALIDA) {
            ultimaTensao = ClassificadorBateria.arredondar(volts + teste.offsetV)
        }
        if (rpm != null) ultimoRpm = rpm
    }

    private fun limparLeituras() {
        ultimaTensao = null
        ultimoRpm = null
    }

    private inline fun mudar(acao: () -> EventoTesteBateria?) {
        var concluido: TesteDaSessao? = null
        val evento = synchronized(trava) {
            val antes = teste.concluido
            val evento = acao()
            if (!antes && teste.concluido && teste.resumo().temMedicao) {
                concluido = TesteDaSessao(veiculoId, teste.resumo()).also { resultadoDaSessao = it }
            }
            publicar()
            evento
        }
        evento?.let { _eventos.tryEmit(it) }
        // Fora da trava: gravar o perfil avisa quem o observa.
        concluido?.let(aoConcluir)
    }

    private fun publicar() {
        val desejada = if (teste.capturando && teste.comRpm) prioridade(teste.etapa) else null
        if (desejada != aplicada) {
            aplicada = desejada
            aplicarPrioridade(desejada)
        }
        _estado.value = EstadoTesteBateria(
            veiculoId = veiculoId,
            etapa = teste.etapa,
            capturando = teste.capturando,
            progresso = teste.progresso(relogioMs()),
            tensao = ultimaTensao,
            rpm = ultimoRpm,
            lvResets = if (teste.capturando) teste.lvResetsNaCaptura else 0,
            quedaDetectada = teste.quedaDetectada,
            vale = teste.valeAtual.takeIf { teste.etapa == EtapaBateria.PARTIDA && teste.capturando },
            aviso = teste.aviso,
            comRpm = teste.comRpm,
            resumo = teste.resumo(),
            curva = teste.curva.toList(),
        )
    }

    /** RPM e, por último, a tensão (`ATRV`; `0142` só se o adaptador não a anunciar). */
    private fun prioridade(etapa: EtapaBateria): PrioridadePolling {
        val conhecidos = suportados()
        val tensao = if (Pids.TENSAO_BATERIA !in conhecidos && TENSAO_MODULO in conhecidos) TENSAO_MODULO else Pids.TENSAO_BATERIA
        val partida = etapa == EtapaBateria.PARTIDA
        return PrioridadePolling(
            comandos = listOf(Pids.RPM, tensao),
            vigiados = if (partida) emptyList() else vigiados().toList(),
            ciclosPorVigiado = if (partida) 0 else CICLOS_POR_VIGIADO,
            toleraEcuMuda = partida,
        )
    }

    companion object {
        /** Tensão do módulo (ECU). */
        const val TENSAO_MODULO = "0142"
        private const val CICLOS_POR_VIGIADO = 5
    }
}
