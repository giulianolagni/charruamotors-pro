package com.charruamotors.oficina.injecao

import kotlin.math.abs

/** Regime do motor em que uma amostra de trim entra nas médias. */
enum class RegimeMotor(val titulo: String) { LENTA("Marcha lenta"), CARGA("Em carga") }

/** Classificação do Trim Total: positivo = a ECU acrescenta combustível (mistura pobre). */
enum class ClasseTrim(val titulo: String, val gravidade: Int) {
    NORMAL("Normal", 0),
    ATENCAO_POBRE("Atenção (pobre)", 1),
    ATENCAO_RICA("Atenção (rica)", 1),
    CRITICO_POBRE("Crítico: mistura pobre", 2),
    CRITICO_RICA("Crítico: mistura rica", 2),
}

/** Média do Trim Total de um regime. */
data class MediaTrim(val media: Double, val amostras: Int) {
    val classe: ClasseTrim get() = AnaliseMistura.classificar(media)
}

/**
 * Regras dos Fuel Trims, puras (JVM): Normal de −8 % a +8 %; Atenção até ±15 %; Crítico acima de +15 %
 * (pobre) ou abaixo de −15 % (rica).
 */
object AnaliseMistura {
    const val LIMITE_NORMAL = 8.0
    const val LIMITE_CRITICO = 15.0

    const val RPM_LENTA_MAXIMO = 1_100.0
    const val VELOCIDADE_LENTA_MAXIMA = 5.0
    const val RPM_CARGA_MINIMO = 1_800.0
    const val CARGA_MINIMA = 45.0

    /** Abaixo disto o motor está desligado ou dando partida. */
    const val RPM_MOTOR_LIGADO = 400.0

    /** Amostras mínimas para a média de um regime valer. */
    const val MIN_AMOSTRAS = 5

    /** Diferença (pontos percentuais) que caracteriza "piorando em carga". */
    private const val PIORA_EM_CARGA = 3.0

    /** `STFT + LTFT`; com só um deles suportado, é o próprio. Null = falta um valor suportado. */
    fun total(stft: Double?, ltft: Double?, temStft: Boolean = true, temLtft: Boolean = true): Double? {
        if (temStft && stft == null || temLtft && ltft == null || !temStft && !temLtft) return null
        return (if (temStft) stft!! else 0.0) + (if (temLtft) ltft!! else 0.0)
    }

    fun classificar(total: Double): ClasseTrim {
        // Uma casa decimal: o ruído de ponto flutuante não muda a faixa.
        val t = kotlin.math.round(total * 10) / 10
        return when {
            abs(t) <= LIMITE_NORMAL -> ClasseTrim.NORMAL
            t > LIMITE_CRITICO -> ClasseTrim.CRITICO_POBRE
            t < -LIMITE_CRITICO -> ClasseTrim.CRITICO_RICA
            t > 0 -> ClasseTrim.ATENCAO_POBRE
            else -> ClasseTrim.ATENCAO_RICA
        }
    }

    /** Lenta (`RPM < 1100` parado) ou carga (`RPM ≥ 1800` ou carga > 45 %); null = fora dos dois. */
    fun regime(rpm: Double?, velocidade: Double?, carga: Double?): RegimeMotor? {
        if (rpm == null || rpm <= RPM_MOTOR_LIGADO) return null
        if (rpm < RPM_LENTA_MAXIMO && velocidade != null && velocidade < VELOCIDADE_LENTA_MAXIMA) return RegimeMotor.LENTA
        if (rpm >= RPM_CARGA_MINIMO || carga != null && carga > CARGA_MINIMA) return RegimeMotor.CARGA
        return null
    }

    /** Dica comparando as médias de lenta e carga (null = regime sem amostras suficientes). */
    fun dica(lenta: Double?, carga: Double?): String {
        if (lenta == null && carga == null) return "Colete amostras em marcha lenta e em carga para o diagnóstico."
        if (lenta == null || carga == null) {
            val falta = if (lenta == null) "em marcha lenta (parado)" else "em carga (≥ 1.800 RPM ou carga > 45 %)"
            val medida = classificar(lenta ?: carga!!).titulo.lowercase()
            return "Regime medido: $medida. Meça também $falta para comparar."
        }
        val pobreL = lenta > LIMITE_NORMAL
        val pobreC = carga > LIMITE_NORMAL
        val ricaL = lenta < -LIMITE_NORMAL
        val ricaC = carga < -LIMITE_NORMAL
        return when {
            pobreC && carga - lenta >= PIORA_EM_CARGA ->
                "Pobre piorando em carga: suspeita de alimentação (bomba, filtro, pressão ou bicos) ou leitura de MAF/MAP."
            pobreL && !pobreC && !ricaC ->
                "Pobre só na lenta, normalizando em carga: suspeita de entrada de ar falsa (mangueiras, junta do coletor, PCV)."
            pobreL && pobreC ->
                "Pobre em toda a faixa: conferir MAF/MAP, pressão de combustível e vazamento no escapamento antes da sonda."
            ricaL && ricaC -> "Rica constante: suspeita de bico injetor, regulador de pressão ou sensor (temperatura, MAF/MAP, sonda)."
            ricaL && !pobreC -> "Rica só na lenta: suspeita de bico gotejando ou purga do cânister aberta."
            ricaC && !pobreL -> "Rica só em carga: suspeita de pressão de combustível alta ou MAF/MAP medindo ar a mais."
            !pobreL && !pobreC && !ricaL && !ricaC -> "Mistura dentro do esperado na lenta e em carga."
            else -> "Comportamento misto entre lenta e carga: conferir MAF/MAP, entradas de ar e a sonda lambda."
        }
    }
}

/** Soma as amostras de Trim Total de um banco, separadas por regime. */
class AcumuladorTrim {
    private val somas = DoubleArray(RegimeMotor.entries.size)
    private val contagens = IntArray(RegimeMotor.entries.size)

    var ultimoTotal: Double? = null
        private set

    fun registrar(total: Double, regime: RegimeMotor?) {
        ultimoTotal = total
        if (regime == null) return
        somas[regime.ordinal] += total
        contagens[regime.ordinal]++
    }

    /** Média do [regime]; null com menos de [AnaliseMistura.MIN_AMOSTRAS] amostras. */
    fun media(regime: RegimeMotor): MediaTrim? {
        val n = contagens[regime.ordinal]
        return if (n < AnaliseMistura.MIN_AMOSTRAS) null else MediaTrim(somas[regime.ordinal] / n, n)
    }

    fun amostras(regime: RegimeMotor): Int = contagens[regime.ordinal]

    fun limpar() {
        somas.fill(0.0)
        contagens.fill(0)
        ultimoTotal = null
    }
}
