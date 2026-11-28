package com.charruamotors.direcao.forcag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/** Maiores forças da sessão, todas em módulo (G). */
data class PicosForcaG(
    val aceleracao: Double = 0.0,
    val frenagem: Double = 0.0,
    val curvaEsquerda: Double = 0.0,
    val curvaDireita: Double = 0.0,
) {
    fun com(f: ForcaG) = PicosForcaG(
        aceleracao = maxOf(aceleracao, f.longitudinal),
        frenagem = maxOf(frenagem, -f.longitudinal),
        curvaEsquerda = maxOf(curvaEsquerda, -f.lateral),
        curvaDireita = maxOf(curvaDireita, f.lateral),
    )
}

data class EstadoForcaG(
    /** Há uma calibração do suporte: sem ela não existem eixos do carro, só "—". */
    val calibrado: Boolean = false,
    val atual: ForcaG? = null,
    /** Últimas leituras (mais antiga primeiro), para o rastro do mostrador. */
    val rastro: List<ForcaG> = emptyList(),
    val picos: PicosForcaG = PicosForcaG(),
)

enum class ResultadoCalibracao { OK, SEM_LEITURAS, EM_MOVIMENTO, POSICAO_INVALIDA }

/**
 * Pipeline do Medidor de Força G, sem Android: recebe as leituras dos sensores, tira a gravidade
 * quando preciso ([FiltroGravidade]), reamostra a ~25 Hz, projeta nos eixos do carro pela
 * [CalibracaoSuporte], suaviza e guarda rastro e picos. Os picos ficam só na memória.
 *
 * Thread-safe: as leituras chegam na thread dos sensores e a UI/sessão consultam de outras.
 *
 * @param relogioMs relógio monotônico, só para saber se a última leitura ainda é recente.
 */
class MedidorForcaG(private val relogioMs: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private val _estado = MutableStateFlow(EstadoForcaG())
    val estado: StateFlow<EstadoForcaG> = _estado.asStateFlow()

    private var calibracao: CalibracaoSuporte? = null
    private var temLinear = true
    private val filtroGravidade = FiltroGravidade()
    private val amostrador = AmostradorEstavel()
    private val suavizador = PassaBaixa(CONSTANTE_SUAVIZACAO_S)

    /** Último segundo de leituras cruas, para a calibração. */
    private val repousoAcelerometro = ArrayDeque<Pair<Long, Vetor3>>()
    private val repousoLinear = ArrayDeque<Pair<Long, Vetor3>>()
    private var ultimoSinalMs: Long? = null
    private var ultimaLeituraMs: Long? = null

    /** Os sensores (re)começaram: [comAceleracaoLinear] = false liga o filtro de gravidade do fallback. */
    @Synchronized
    fun reiniciarSinal(comAceleracaoLinear: Boolean) {
        temLinear = comAceleracaoLinear
        filtroGravidade.reiniciar()
        amostrador.reiniciar()
        suavizador.reiniciar()
        repousoAcelerometro.clear()
        repousoLinear.clear()
        ultimoSinalMs = null
        ultimaLeituraMs = null
        _estado.value = _estado.value.copy(atual = null, rastro = emptyList())
    }

    /** `TYPE_ACCELEROMETER` (com gravidade). */
    @Synchronized
    fun acelerometro(v: Vetor3, instanteNs: Long) {
        if (!v.finito) return
        ultimoSinalMs = relogioMs()
        guardar(repousoAcelerometro, v, instanteNs)
        if (!temLinear) processar(filtroGravidade.remover(v, instanteNs), instanteNs)
    }

    /** `TYPE_LINEAR_ACCELERATION` (sem gravidade). */
    @Synchronized
    fun aceleracaoLinear(v: Vetor3, instanteNs: Long) {
        if (!v.finito || !temLinear) return
        guardar(repousoLinear, v, instanteNs)
        processar(v, instanteNs)
    }

    /** Calibração salva (ou nenhuma). Trocar de calibração zera rastro e picos. */
    @Synchronized
    fun definirCalibracao(nova: CalibracaoSuporte?) {
        if (nova == calibracao) return
        calibracao = nova
        suavizador.reiniciar()
        ultimaLeituraMs = null
        _estado.value = EstadoForcaG(calibrado = nova != null)
    }

    /**
     * "Zerar / Calibrar suporte": usa o último segundo, com o carro parado. Devolve a calibração
     * aplicada (para ser salva) ou o motivo da recusa.
     */
    @Synchronized
    fun calibrar(rotacao: RotacaoTela): Pair<ResultadoCalibracao, CalibracaoSuporte?> {
        val agora = relogioMs()
        val recente = ultimoSinalMs?.let { agora - it <= VALIDADE_MS } == true
        if (!recente || repousoAcelerometro.size < MIN_AMOSTRAS) return ResultadoCalibracao.SEM_LEITURAS to null
        val duracao = repousoAcelerometro.last().first - repousoAcelerometro.first().first
        if (duracao < DURACAO_MINIMA_NS) return ResultadoCalibracao.SEM_LEITURAS to null

        val gravidade = media(repousoAcelerometro)
        val dispersao = sqrt(repousoAcelerometro.sumOf { (_, v) -> (v - gravidade).let { it.escalar(it) } } / repousoAcelerometro.size)
        val vies = if (temLinear && repousoLinear.isNotEmpty()) media(repousoLinear) else Vetor3.ZERO
        if (dispersao > DISPERSAO_MAXIMA || abs(gravidade.norma - GRAVIDADE_MS2) > DESVIO_GRAVIDADE_MAXIMO || vies.norma > VIES_MAXIMO) {
            return ResultadoCalibracao.EM_MOVIMENTO to null
        }
        val nova = CalibracaoSuporte.de(gravidade, rotacao, vies) ?: return ResultadoCalibracao.POSICAO_INVALIDA to null
        calibracao = nova
        suavizador.reiniciar()
        ultimaLeituraMs = null
        _estado.value = EstadoForcaG(calibrado = true)
        return ResultadoCalibracao.OK to nova
    }

    /** Leitura recente para o log; null sem calibração, sem sensor ou com leitura velha. */
    @Synchronized
    fun leituraAtual(): ForcaG? {
        val quando = ultimaLeituraMs ?: return null
        return _estado.value.atual?.takeIf { relogioMs() - quando <= VALIDADE_MS }
    }

    @Synchronized
    fun zerarPicos() {
        _estado.value = _estado.value.copy(picos = PicosForcaG(), rastro = emptyList())
    }

    private fun processar(linear: Vetor3, instanteNs: Long) {
        val media = amostrador.adicionar(linear, instanteNs) ?: return
        val suave = suavizador.aplicar(media, instanteNs)
        val c = calibracao ?: return
        val leitura = c.projetar(suave)
        ultimaLeituraMs = relogioMs()
        val anterior = _estado.value
        _estado.value = anterior.copy(
            atual = leitura,
            rastro = (anterior.rastro + leitura).takeLast(TAMANHO_RASTRO),
            picos = anterior.picos.com(leitura),
        )
    }

    private fun guardar(fila: ArrayDeque<Pair<Long, Vetor3>>, v: Vetor3, instanteNs: Long) {
        fila.addLast(instanteNs to v)
        while (fila.isNotEmpty() && instanteNs - fila.first().first > JANELA_REPOUSO_NS) fila.removeFirst()
    }

    private fun media(fila: Collection<Pair<Long, Vetor3>>): Vetor3 =
        fila.fold(Vetor3.ZERO) { soma, (_, v) -> soma + v } * (1.0 / fila.size)

    companion object {
        /** ~2 s de rastro a 25 Hz. */
        const val TAMANHO_RASTRO = 50
        const val VALIDADE_MS = 1_000L
        private const val CONSTANTE_SUAVIZACAO_S = 0.12
        private const val JANELA_REPOUSO_NS = 1_000_000_000L
        private const val DURACAO_MINIMA_NS = 500_000_000L
        private const val MIN_AMOSTRAS = 10

        /** Vibração do motor em marcha lenta passa; sacolejo e carro andando, não (m/s²). */
        private const val DISPERSAO_MAXIMA = 1.0
        private const val DESVIO_GRAVIDADE_MAXIMO = 1.2
        private const val VIES_MAXIMO = 1.5
    }
}
