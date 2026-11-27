package com.charruamotors.oficina.bateria

import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd
import kotlin.math.roundToLong

/** Uma amostra de tensão (`ATRV` ou `0142`) durante o teste. */
sealed interface LeituraTensao {
    data class Valida(val volts: Double) : LeituraTensao

    /** O ELM327 reiniciou por subtensão (`LV RESET`): típico do vale da partida. Não aborta o teste. */
    data object LvReset : LeituraTensao

    /** Sem resposta no prazo ou resposta ilegível (amostra perdida). */
    data object Perdida : LeituraTensao

    companion object {
        /** Interpreta a resposta crua do `ATRV`; [semPrompt] = o adaptador não respondeu no prazo. */
        fun deResposta(bruto: String, semPrompt: Boolean = false): LeituraTensao {
            if (semPrompt) return Perdida
            if (ObdParser.adaptadorReiniciou(bruto)) return LvReset
            return when (val r = ObdParser.parseTensao(bruto)) {
                is RespostaObd.Ok -> Valida(r.valor)
                else -> Perdida
            }
        }
    }
}

/** As 3 etapas do teste guiado (a 3ª em duas fases: marcha lenta e ~2.000 RPM com cargas). */
enum class EtapaBateria(val numero: Int) {
    REPOUSO(1),
    PARTIDA(2),
    ALTERNADOR_LENTA(3),
    ALTERNADOR_CARGA(3),
    CONCLUIDO(4),
}

/** Gravidade comum dos vereditos, para o resumo do laudo (0 = bom, 1 = atenção, 2 = ruim). */
interface Veredito {
    val gravidade: Int
}

enum class VereditoRepouso(override val gravidade: Int) : Veredito { BOM(0), ATENCAO(1), FRACA(2) }

/** [INCONCLUSIVO] nunca reprova a bateria: a medição da partida não é confiável (conferir nos polos). */
enum class VereditoPartida(override val gravidade: Int) : Veredito { BOM(0), ATENCAO(1), CRITICA(2), INCONCLUSIVO(1) }

enum class VereditoAlternador(override val gravidade: Int) : Veredito { SUBCARGA(2), NORMAL(0), SOBRETENSAO(2) }

/** Por que a etapa da partida ficou [VereditoPartida.INCONCLUSIVO]. */
enum class MotivoInconclusivo {
    /** `LV RESET`, leituras interrompidas ou conexão perdida durante o arranque. */
    COMUNICACAO,

    /** O motor pegou sem que nenhuma queda de tensão fosse amostrada. */
    QUEDA_NAO_CAPTURADA,
}

/**
 * Faixas para a tensão **lida pela porta OBD2** (0,3–0,7 V abaixo dos polos por causa do diodo e do
 * chicote), já com o ajuste do adaptador aplicado:
 * - repouso: bom ≥ 12,1 V; atenção 11,7–12,09 V; fraca < 11,7 V;
 * - partida, gasolina/flex: bom ≥ 9,2 V; atenção 8,3–9,19 V; crítica < 8,3 V;
 * - partida, diesel: bom ≥ 8,8 V; atenção 7,8–8,79 V; crítica < 7,8 V;
 * - alternador (média): subcarga < 13,3 V; normal 13,3–14,9 V; sobretensão/regulador > 14,9 V.
 *
 * Os valores chegam com 2 casas ([arredondar]) para não caírem numa faixa por ruído de ponto flutuante.
 */
object ClassificadorBateria {
    const val REPOUSO_BOM = 12.1
    const val REPOUSO_FRACA = 11.7
    const val PARTIDA_OTTO_BOM = 9.2
    const val PARTIDA_OTTO_CRITICA = 8.3
    const val PARTIDA_DIESEL_BOM = 8.8
    const val PARTIDA_DIESEL_CRITICA = 7.8
    const val ALTERNADOR_MIN = 13.3
    const val ALTERNADOR_MAX = 14.9

    /** Ripple pico a pico (estimado) acima disso sugere diodo da ponte retificadora em curto/aberto. */
    const val RIPPLE_ALTO = 0.5

    fun repouso(volts: Double): VereditoRepouso {
        val v = arredondar(volts)
        return when {
            v >= REPOUSO_BOM -> VereditoRepouso.BOM
            v >= REPOUSO_FRACA -> VereditoRepouso.ATENCAO
            else -> VereditoRepouso.FRACA
        }
    }

    fun partida(vMin: Double, diesel: Boolean): VereditoPartida {
        val v = arredondar(vMin)
        return when {
            v >= partidaBom(diesel) -> VereditoPartida.BOM
            v >= partidaCritica(diesel) -> VereditoPartida.ATENCAO
            else -> VereditoPartida.CRITICA
        }
    }

    fun partidaBom(diesel: Boolean) = if (diesel) PARTIDA_DIESEL_BOM else PARTIDA_OTTO_BOM

    fun partidaCritica(diesel: Boolean) = if (diesel) PARTIDA_DIESEL_CRITICA else PARTIDA_OTTO_CRITICA

    fun alternador(media: Double): VereditoAlternador {
        val v = arredondar(media)
        return when {
            v < ALTERNADOR_MIN -> VereditoAlternador.SUBCARGA
            v <= ALTERNADOR_MAX -> VereditoAlternador.NORMAL
            else -> VereditoAlternador.SOBRETENSAO
        }
    }

    fun arredondar(v: Double, casas: Int = 2): Double {
        var fator = 1.0
        repeat(casas) { fator *= 10 }
        return (v * fator).roundToLong() / fator
    }
}

/** v2.11.1: o que cada linha horizontal do gráfico de tensão marca. */
enum class TipoReferenciaBateria { PARTIDA_MINIMA, ALTERNADOR_MINIMO, ALTERNADOR_MAXIMO }

data class ReferenciaBateria(val tipo: TipoReferenciaBateria, val volts: Double)

/** Linhas de referência do gráfico: o limiar "bom" da partida (diesel ou Otto) e a faixa do alternador. */
object ReferenciasBateria {
    fun de(diesel: Boolean): List<ReferenciaBateria> = listOf(
        ReferenciaBateria(TipoReferenciaBateria.PARTIDA_MINIMA, ClassificadorBateria.partidaBom(diesel)),
        ReferenciaBateria(TipoReferenciaBateria.ALTERNADOR_MINIMO, ClassificadorBateria.ALTERNADOR_MIN),
        ReferenciaBateria(TipoReferenciaBateria.ALTERNADOR_MAXIMO, ClassificadorBateria.ALTERNADOR_MAX),
    )
}

/**
 * Ajuste do adaptador: volts somados à leitura OBD2 para compensar a queda fixa do diodo/chicote,
 * conferida com um multímetro nos polos. Guardado por veículo.
 */
object OffsetAdaptador {
    const val MINIMO = -1.0
    const val MAXIMO = 1.5
    const val PASSO = 0.1

    fun limitar(volts: Double): Double =
        if (!volts.isFinite()) 0.0 else ClassificadorBateria.arredondar(volts.coerceIn(MINIMO, MAXIMO), 1)
}

data class ResultadoRepouso(
    /** Média da segunda metade da janela (tensão já estabilizada). */
    val tensao: Double,
    val minima: Double,
    val maxima: Double,
    val amostras: Int,
    /** Variação ≤ [TesteBateria.VARIACAO_ESTAVEL] V na janela medida. */
    val estavel: Boolean,
    val veredito: VereditoRepouso,
) {
    /** Acima de ~13,2 V em "repouso" o motor (alternador) provavelmente estava ligado. */
    val motorPareceLigado: Boolean get() = tensao >= TesteBateria.TENSAO_MOTOR_LIGADO
}

data class ResultadoPartida(
    /** Tensão de referência antes da partida (a do repouso, ou a primeira amostra da captura). */
    val tensaoInicial: Double?,
    /** Menor tensão válida capturada; null = nenhuma amostra. */
    val vMin: Double?,
    /** Tempo abaixo de (referência − [TesteBateria.QUEDA_MINIMA_PARTIDA]) até a tensão se recuperar. */
    val duracaoQuedaMs: Long,
    val lvResets: Int,
    val amostrasPerdidas: Int,
    val motorPegou: Boolean,
    val veredito: VereditoPartida,
    val motivo: MotivoInconclusivo? = null,
) {
    val inconclusivo: Boolean get() = veredito == VereditoPartida.INCONCLUSIVO
}

data class ResultadoCarga(
    val media: Double,
    val pico: Double,
    val minima: Double,
    /** Ripple pico a pico estimado (desvio em torno da média móvel, limitado pela resolução de 0,1 V). */
    val ripple: Double,
    val amostras: Int,
    val veredito: VereditoAlternador,
) {
    val rippleAlto: Boolean get() = ripple > ClassificadorBateria.RIPPLE_ALTO
}

/** Instantâneo imutável dos resultados (tela, texto compartilhável, histórico do carro e laudo PDF). */
data class ResumoTesteBateria(
    val repouso: ResultadoRepouso? = null,
    val partida: ResultadoPartida? = null,
    val lenta: ResultadoCarga? = null,
    val carga: ResultadoCarga? = null,
    val puladas: Set<EtapaBateria> = emptySet(),
    val diesel: Boolean = false,
    val offsetV: Double = 0.0,
) {
    val temMedicao: Boolean get() = repouso != null || partida != null || lenta != null || carga != null
}

/** Estatísticas de uma série de tensões (usadas pelas etapas e testadas isoladamente). */
object EstatisticaTensao {

    fun media(valores: List<Double>): Double = valores.sum() / valores.size

    /**
     * Ripple pico a pico estimado: amplitude dos desvios em relação a uma média móvel centrada de
     * [janela] amostras, o que separa a oscilação rápida da deriva lenta (ex.: RPM subindo).
     * Com menos amostras que a janela, a própria amplitude (máx − mín).
     */
    fun ripple(valores: List<Double>, janela: Int = JANELA_RIPPLE): Double {
        if (valores.isEmpty()) return 0.0
        if (valores.size < janela) return ClassificadorBateria.arredondar(valores.max() - valores.min())
        val meia = janela / 2
        val desvios = valores.indices.map { i ->
            val de = (i - meia).coerceAtLeast(0)
            val ate = (i + meia).coerceAtMost(valores.lastIndex)
            valores[i] - valores.subList(de, ate + 1).average()
        }
        return ClassificadorBateria.arredondar(desvios.max() - desvios.min())
    }

    fun carga(valores: List<Double>): ResultadoCarga {
        require(valores.isNotEmpty()) { "Sem amostras" }
        val media = ClassificadorBateria.arredondar(media(valores))
        return ResultadoCarga(
            media = media,
            pico = valores.max(),
            minima = valores.min(),
            ripple = ripple(valores),
            amostras = valores.size,
            veredito = ClassificadorBateria.alternador(media),
        )
    }

    const val JANELA_RIPPLE = 5
}

/** O que aconteceu com a captura depois de uma amostra (null = segue capturando). */
sealed interface EventoTesteBateria {
    data class EtapaConcluida(val etapa: EtapaBateria) : EventoTesteBateria

    /** Na etapa 2 não houve queda de tensão nem o motor ligou em [TesteBateria.ESPERA_MAXIMA_PARTIDA_MS]. */
    data object PartidaNaoDetectada : EventoTesteBateria

    /** Etapa 2 iniciada com o motor já girando. */
    data object MotorJaLigado : EventoTesteBateria

    /** A condição de RPM da etapa (motor desligado, lenta ou ~2.000) não foi mantida no prazo. */
    data object CondicaoNaoAtendida : EventoTesteBateria

    /** Poucas amostras válidas na janela (adaptador mudo, Bluetooth instável): dá para repetir. */
    data object LeiturasInsuficientes : EventoTesteBateria
}

/** O que falta para a janela de medição andar (orientação na tela). */
enum class AvisoCaptura { DESLIGUE_O_MOTOR, LIGUE_O_MOTOR, VOLTE_A_LENTA, ACELERE_A_2000 }

/** Um ponto da curva capturada; [volts] null = amostra perdida (interrompe a curva). */
data class PontoCurva(val instanteMs: Long, val volts: Double?)

/**
 * Máquina de estados do Teste Guiado de Bateria, Partida e Alternador, pura (sem Android nem
 * corrotinas). Cada amostra chega em [registrar] com o instante e o RPM; a etapa termina sozinha e o
 * teste para em [capturando] = false, esperando o usuário seguir as instruções da próxima.
 *
 * 1. **Repouso** — motor desligado (`RPM == 0`) por [DURACAO_JANELA_MS]; vale a média da 2ª metade.
 * 2. **Partida** — guarda a menor tensão até o RPM passar de [RPM_MOTOR_LIGADO] (e mais
 *    [APOS_MOTOR_PEGAR_MS]). `LV RESET`, lacuna nas leituras ou conexão perdida durante o arranque, e
 *    motor ligado sem queda amostrada, dão **Inconclusivo**.
 * 3. **Alternador** — [DURACAO_JANELA_MS] em lenta e outros a ~2.000 RPM com cargas ligadas.
 *
 * Sem RPM ([comRpm] = false: Modos 21/22, só o `ATRV`) o motor é considerado ligado acima de
 * [TENSAO_MOTOR_LIGADO] V e as janelas da etapa 3 não conferem a rotação.
 *
 * Leituras abaixo de [TENSAO_MINIMA_VALIDA] V (cruas) são espúrias e contam como perdidas.
 */
class TesteBateria {

    /** Há RPM nas amostras (OBD2 padrão). */
    var comRpm: Boolean = true

    /** Motor diesel: muda só as faixas da partida (o resultado já medido é reclassificado). */
    var diesel: Boolean = false
        set(valor) {
            field = valor
            val p = resultadoPartida ?: return
            val vMin = p.vMin ?: return
            if (!p.inconclusivo) resultadoPartida = p.copy(veredito = ClassificadorBateria.partida(vMin, valor))
        }

    /** Ajuste do adaptador somado a cada leitura. Trocá-lo com resultados medidos exige [reiniciar]. */
    var offsetV: Double = 0.0

    var etapa: EtapaBateria = EtapaBateria.REPOUSO
        private set

    var capturando: Boolean = false
        private set

    var resultadoRepouso: ResultadoRepouso? = null
        private set
    var resultadoPartida: ResultadoPartida? = null
        private set
    var resultadoLenta: ResultadoCarga? = null
        private set
    var resultadoCarga: ResultadoCarga? = null
        private set

    var aviso: AvisoCaptura? = null
        private set

    private val _puladas = mutableSetOf<EtapaBateria>()

    /** Etapas puladas pelo usuário (aparecem como "não realizada" no laudo). */
    val puladas: Set<EtapaBateria> get() = _puladas

    /** Curva de todas as capturas, num tempo contínuo (as pausas entre etapas são comprimidas). */
    private val _curva = ArrayList<PontoCurva>()
    val curva: List<PontoCurva> get() = _curva

    /** v2.11.1: o ponto da curva em que a partida fez o vale (o primeiro com a menor tensão); null sem partida. */
    var pontoVale: PontoCurva? = null
        private set

    /** Amostras da janela de medição em curso, já com o ajuste. */
    private val amostras = mutableListOf<Pair<Long, Double>>()
    private var inicioCapturaMs = 0L
    private var inicioJanelaMs = 0L
    private var deslocamentoCurvaMs = 0L
    private var viuCondicaoRpm = false
    private var lvResets = 0
    private var perdidas = 0

    // Etapa 2
    private var referenciaPartida: Double? = null
    private var vMinPartida: Double? = null
    private var ultimaValidaMs = 0L
    private var inicioQuedaMs: Long? = null
    private var fimQuedaMs: Long? = null
    private var motorPegouMs: Long? = null
    private var interrompida = false
    private var viuLvReset = false

    /** Menor tensão da captura da partida (o vale mostrado na tela). */
    val valeAtual: Double? get() = vMinPartida

    /** A queda de partida já foi detectada nesta captura. */
    val quedaDetectada: Boolean get() = inicioQuedaMs != null

    val lvResetsNaCaptura: Int get() = lvResets

    val concluido: Boolean get() = etapa == EtapaBateria.CONCLUIDO

    fun resumo() = ResumoTesteBateria(
        resultadoRepouso, resultadoPartida, resultadoLenta, resultadoCarga, _puladas.toSet(), diesel, offsetV,
    )

    /** Começa (ou recomeça) a captura da etapa atual. */
    fun iniciarCaptura(agoraMs: Long) {
        check(etapa != EtapaBateria.CONCLUIDO) { "Teste já concluído" }
        amostras.clear()
        inicioCapturaMs = agoraMs
        inicioJanelaMs = agoraMs
        viuCondicaoRpm = false
        aviso = null
        lvResets = 0
        perdidas = 0
        referenciaPartida = resultadoRepouso?.tensao
        vMinPartida = null
        if (etapa == EtapaBateria.PARTIDA) pontoVale = null
        ultimaValidaMs = agoraMs
        inicioQuedaMs = null
        fimQuedaMs = null
        motorPegouMs = null
        interrompida = false
        viuLvReset = false
        val ultimo = _curva.lastOrNull()
        deslocamentoCurvaMs = if (ultimo == null) -agoraMs else ultimo.instanteMs + PAUSA_NA_CURVA_MS - agoraMs
        if (ultimo != null) _curva += PontoCurva(ultimo.instanteMs + 1, null)
        capturando = true
    }

    fun cancelarCaptura() {
        capturando = false
        aviso = null
        amostras.clear()
    }

    /** Pula a etapa atual (sem resultado) e vai para a próxima. */
    fun pular() {
        if (etapa == EtapaBateria.CONCLUIDO) return
        cancelarCaptura()
        _puladas += etapa
        when (etapa) {
            EtapaBateria.REPOUSO -> resultadoRepouso = null
            EtapaBateria.PARTIDA -> {
                resultadoPartida = null
                pontoVale = null
            }
            EtapaBateria.ALTERNADOR_LENTA -> resultadoLenta = null
            EtapaBateria.ALTERNADOR_CARGA -> resultadoCarga = null
            EtapaBateria.CONCLUIDO -> Unit
        }
        etapa = proxima(etapa)
    }

    /** Recomeça o teste do zero. */
    fun reiniciar() {
        cancelarCaptura()
        etapa = EtapaBateria.REPOUSO
        resultadoRepouso = null
        resultadoPartida = null
        resultadoLenta = null
        resultadoCarga = null
        _puladas.clear()
        _curva.clear()
        pontoVale = null
    }

    /** Progresso da janela (0–1); null quando indeterminado (etapa 2) ou sem captura. */
    fun progresso(agoraMs: Long): Float? {
        if (!capturando || etapa == EtapaBateria.PARTIDA) return null
        if (aviso != null) return 0f
        return ((agoraMs - inicioJanelaMs).toFloat() / DURACAO_JANELA_MS).coerceIn(0f, 1f)
    }

    /** Entrega uma amostra; devolve o evento se a captura terminou com ela. */
    fun registrar(leitura: LeituraTensao, agoraMs: Long, rpm: Double? = null): EventoTesteBateria? {
        if (!capturando) return null
        val volts = (leitura as? LeituraTensao.Valida)?.volts
            ?.takeIf { it >= TENSAO_MINIMA_VALIDA && it <= TENSAO_MAXIMA_VALIDA }
            ?.let { ClassificadorBateria.arredondar(it + offsetV) }
        if (leitura == LeituraTensao.LvReset) lvResets++
        if (volts == null) perdidas++
        _curva += PontoCurva(agoraMs + deslocamentoCurvaMs, volts)
        if (_curva.size > MAX_PONTOS_CURVA) _curva.removeAt(0)
        val rotacao = rpm?.takeIf { comRpm && it.isFinite() }
        return when (etapa) {
            EtapaBateria.REPOUSO -> avaliarRepouso(volts, rotacao, agoraMs)
            EtapaBateria.PARTIDA -> avaliarPartida(leitura, volts, rotacao, agoraMs)
            EtapaBateria.ALTERNADOR_LENTA ->
                avaliarCarga(volts, rotacao, agoraMs, RPM_LENTA, AvisoCaptura.VOLTE_A_LENTA) { resultadoLenta = it }
            EtapaBateria.ALTERNADOR_CARGA ->
                avaliarCarga(volts, rotacao, agoraMs, RPM_CARGA, AvisoCaptura.ACELERE_A_2000) { resultadoCarga = it }
            EtapaBateria.CONCLUIDO -> null
        }
    }

    /** Prazos que vencem mesmo sem amostras chegando (adaptador mudo na partida). */
    fun tique(agoraMs: Long): EventoTesteBateria? {
        if (!capturando) return null
        if (etapa == EtapaBateria.PARTIDA) {
            if (agoraMs - ultimaValidaMs > LACUNA_MAXIMA_MS) interrompida = true
            return prazoDaPartida(agoraMs)
        }
        if (agoraMs - inicioCapturaMs < ESPERA_MAXIMA_ETAPA_MS) return null
        return falhar(if (viuCondicaoRpm || !comRpm) EventoTesteBateria.LeiturasInsuficientes else EventoTesteBateria.CondicaoNaoAtendida)
    }

    /**
     * A conexão caiu. Na partida em curso a etapa vira **Inconclusivo** (o teste segue para o
     * alternador); nas demais a captura é só cancelada.
     */
    fun perderComunicacao(agoraMs: Long): EventoTesteBateria? {
        if (!capturando) return null
        if (etapa != EtapaBateria.PARTIDA) {
            cancelarCaptura()
            return null
        }
        interrompida = true
        return concluirPartida(agoraMs, motorPegou = false)
    }

    private fun avaliarRepouso(volts: Double?, rpm: Double?, agoraMs: Long): EventoTesteBateria? {
        if (rpm != null && rpm > 0) {
            reabrirJanela(agoraMs, AvisoCaptura.DESLIGUE_O_MOTOR)
        } else {
            if (rpm != null) {
                viuCondicaoRpm = true
                aviso = null
            }
            if (volts != null) amostras += agoraMs to volts
        }
        val pronta = aviso == null && (!comRpm || viuCondicaoRpm) && agoraMs - inicioJanelaMs >= DURACAO_JANELA_MS
        if (!pronta) return tique(agoraMs)
        val janela = amostras.filter { it.first >= inicioJanelaMs + DURACAO_JANELA_MS / 2 }.map { it.second }
        if (janela.size < MIN_AMOSTRAS) return falhar(EventoTesteBateria.LeiturasInsuficientes)
        val tensao = ClassificadorBateria.arredondar(EstatisticaTensao.media(janela))
        resultadoRepouso = ResultadoRepouso(
            tensao = tensao,
            minima = janela.min(),
            maxima = janela.max(),
            amostras = janela.size,
            estavel = janela.max() - janela.min() <= VARIACAO_ESTAVEL + 1e-9,
            veredito = ClassificadorBateria.repouso(tensao),
        )
        return concluirEtapa()
    }

    private fun avaliarPartida(leitura: LeituraTensao, volts: Double?, rpm: Double?, agoraMs: Long): EventoTesteBateria? {
        if (leitura == LeituraTensao.LvReset) {
            viuLvReset = true
            interrompida = true
            if (inicioQuedaMs == null) inicioQuedaMs = agoraMs
        }
        if (volts != null) {
            val lacuna = agoraMs - ultimaValidaMs > LACUNA_MAXIMA_MS
            ultimaValidaMs = agoraMs
            if (lacuna) interrompida = true
            if (referenciaPartida == null) referenciaPartida = volts
            val referencia = referenciaPartida ?: volts
            if (volts < (vMinPartida ?: Double.MAX_VALUE)) pontoVale = _curva.last()
            vMinPartida = minOf(vMinPartida ?: volts, volts)
            if (inicioQuedaMs == null && volts <= referencia - QUEDA_MINIMA_PARTIDA + 1e-9) inicioQuedaMs = agoraMs
            if (inicioQuedaMs != null && fimQuedaMs == null && volts >= referencia - RECUPERACAO_PARTIDA - 1e-9) {
                fimQuedaMs = agoraMs
            }
            // Voltou com o motor parado e a tensão no lugar: a falha foi antes do arranque, não durante.
            if (interrompida && !viuLvReset && inicioQuedaMs == null && rpm == 0.0) interrompida = false
        }
        if (rpm != null && rpm <= RPM_MOTOR_LIGADO) viuCondicaoRpm = true
        val ligado = if (comRpm) rpm != null && rpm > RPM_MOTOR_LIGADO else volts != null && volts >= TENSAO_MOTOR_LIGADO
        if (ligado && motorPegouMs == null) {
            // Sem RPM, a tensão alta só vale depois de uma queda ou de uma interrupção (senão é o repouso).
            if (inicioQuedaMs == null && !interrompida) {
                // Nunca foi visto parado: já estava ligado quando a captura começou.
                if (comRpm && !viuCondicaoRpm) return falhar(EventoTesteBateria.MotorJaLigado)
                if (comRpm) motorPegouMs = agoraMs
            } else {
                motorPegouMs = agoraMs
            }
        }
        return prazoDaPartida(agoraMs)
    }

    private fun prazoDaPartida(agoraMs: Long): EventoTesteBateria? {
        val pegou = motorPegouMs
        if (pegou != null) {
            return if (agoraMs - pegou >= APOS_MOTOR_PEGAR_MS) concluirPartida(agoraMs, motorPegou = true) else null
        }
        val queda = inicioQuedaMs
        if (queda != null) {
            return if (agoraMs - queda >= DURACAO_MAXIMA_QUEDA_MS) concluirPartida(agoraMs, motorPegou = false) else null
        }
        if (agoraMs - inicioCapturaMs < ESPERA_MAXIMA_PARTIDA_MS) return null
        return if (interrompida) concluirPartida(agoraMs, motorPegou = false) else falhar(EventoTesteBateria.PartidaNaoDetectada)
    }

    private fun concluirPartida(agoraMs: Long, motorPegou: Boolean): EventoTesteBateria {
        val vMin = vMinPartida
        val motivo = when {
            interrompida -> MotivoInconclusivo.COMUNICACAO
            vMin == null || inicioQuedaMs == null -> MotivoInconclusivo.QUEDA_NAO_CAPTURADA
            else -> null
        }
        val inicio = inicioQuedaMs
        resultadoPartida = ResultadoPartida(
            tensaoInicial = referenciaPartida,
            vMin = vMin,
            duracaoQuedaMs = if (inicio == null) 0 else (fimQuedaMs ?: agoraMs) - inicio,
            lvResets = lvResets,
            amostrasPerdidas = perdidas,
            motorPegou = motorPegou,
            veredito = if (motivo != null || vMin == null) VereditoPartida.INCONCLUSIVO else ClassificadorBateria.partida(vMin, diesel),
            motivo = motivo,
        )
        return concluirEtapa()
    }

    private inline fun avaliarCarga(
        volts: Double?,
        rpm: Double?,
        agoraMs: Long,
        faixaRpm: ClosedFloatingPointRange<Double>,
        orientacao: AvisoCaptura,
        guardar: (ResultadoCarga) -> Unit,
    ): EventoTesteBateria? {
        if (rpm != null && rpm !in faixaRpm) {
            reabrirJanela(agoraMs, if (rpm <= RPM_MOTOR_LIGADO) AvisoCaptura.LIGUE_O_MOTOR else orientacao)
        } else {
            if (rpm != null) {
                // A janela começa na primeira leitura dentro da faixa.
                if (aviso != null) inicioJanelaMs = agoraMs
                viuCondicaoRpm = true
                aviso = null
            }
            if (volts != null) amostras += agoraMs to volts
        }
        val pronta = aviso == null && (!comRpm || viuCondicaoRpm) && agoraMs - inicioJanelaMs >= DURACAO_JANELA_MS
        if (!pronta) return tique(agoraMs)
        val valores = amostras.map { it.second }
        if (valores.size < MIN_AMOSTRAS) return falhar(EventoTesteBateria.LeiturasInsuficientes)
        guardar(EstatisticaTensao.carga(valores))
        return concluirEtapa()
    }

    private fun reabrirJanela(agoraMs: Long, orientacao: AvisoCaptura) {
        amostras.clear()
        inicioJanelaMs = agoraMs
        aviso = orientacao
    }

    private fun falhar(evento: EventoTesteBateria): EventoTesteBateria {
        cancelarCaptura()
        return evento
    }

    private fun concluirEtapa(): EventoTesteBateria {
        val concluida = etapa
        capturando = false
        aviso = null
        _puladas -= concluida
        etapa = proxima(concluida)
        return EventoTesteBateria.EtapaConcluida(concluida)
    }

    private fun proxima(e: EtapaBateria) = when (e) {
        EtapaBateria.REPOUSO -> EtapaBateria.PARTIDA
        EtapaBateria.PARTIDA -> EtapaBateria.ALTERNADOR_LENTA
        EtapaBateria.ALTERNADOR_LENTA -> EtapaBateria.ALTERNADOR_CARGA
        EtapaBateria.ALTERNADOR_CARGA, EtapaBateria.CONCLUIDO -> EtapaBateria.CONCLUIDO
    }

    companion object {
        /** Duração das janelas de repouso, lenta e carga. */
        const val DURACAO_JANELA_MS = 3_000L

        /** Prazo total das etapas 1 e 3 (inclui o tempo para o motorista atender a condição). */
        const val ESPERA_MAXIMA_ETAPA_MS = 60_000L

        /** Sem queda nem motor ligado até aqui na etapa 2: partida não detectada. */
        const val ESPERA_MAXIMA_PARTIDA_MS = 30_000L

        /** Depois da queda, encerra a etapa 2 mesmo sem o motor pegar. */
        const val DURACAO_MAXIMA_QUEDA_MS = 10_000L

        /** Segue capturando este tempo depois de o motor pegar. */
        const val APOS_MOTOR_PEGAR_MS = 1_000L

        /** Intervalo sem tensão válida que caracteriza perda de comunicação na partida. */
        const val LACUNA_MAXIMA_MS = 1_500L

        /** Queda mínima (V) em relação à referência para considerar que o motor de arranque entrou. */
        const val QUEDA_MINIMA_PARTIDA = 0.8

        /** A queda terminou quando a tensão volta a menos disto (V) da referência. */
        const val RECUPERACAO_PARTIDA = 0.3

        /** Sem RPM: acima disso (V) o alternador está carregando. */
        const val TENSAO_MOTOR_LIGADO = 13.2

        /** Leituras cruas fora desta faixa são espúrias. */
        const val TENSAO_MINIMA_VALIDA = 5.0
        const val TENSAO_MAXIMA_VALIDA = 25.0

        const val RPM_MOTOR_LIGADO = 400.0

        /** Marcha lenta (inclui motor frio) e a rotação de carga (~2.000). */
        val RPM_LENTA = 401.0..1_500.0
        val RPM_CARGA = 1_700.0..2_600.0

        /** Variação máxima (V) na janela do repouso para a tensão ser considerada estabilizada. */
        const val VARIACAO_ESTAVEL = 0.2

        /** Mínimo de amostras válidas numa janela de medição. */
        const val MIN_AMOSTRAS = 3

        private const val PAUSA_NA_CURVA_MS = 500L
        private const val MAX_PONTOS_CURVA = 3_000
    }
}
