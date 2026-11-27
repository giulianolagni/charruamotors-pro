package com.charruamotors.domain.calculo

/** Estatísticas detalhadas da viagem em curso (v2.6.0). */
data class EstatisticasPercurso(
    val distanciaKm: Double = 0.0,
    val segundosMovimento: Double = 0.0,
    /** Parado, com o motor em marcha lenta (ou desligado pelo start-stop). */
    val segundosParado: Double = 0.0,
    /** Maior velocidade aceita (nunca o `010D = FF`); null = nenhuma leitura. */
    val velocidadeMaximaKmh: Double? = null,
    val rpmMaximo: Double? = null,
    /** ∫ RPM dt e o tempo correspondente, só com o motor girando. */
    val rpmSegundos: Double = 0.0,
    val segundosMotor: Double = 0.0,
    val litros: Double = 0.0,
) {
    val segundosTotais: Double get() = segundosMovimento + segundosParado

    /** Média incluindo o tempo parado; null sem tempo contado. */
    val velocidadeMediaKmh: Double? get() = media(segundosTotais)

    val velocidadeMediaMovimentoKmh: Double? get() = media(segundosMovimento)

    val rpmMedio: Double? get() = if (segundosMotor > 0) rpmSegundos / segundosMotor else null

    val kmPorLitro: Double? get() = if (temConsumo) distanciaKm / litros else null

    val litrosPor100Km: Double? get() = if (temConsumo) litros * 100.0 / distanciaKm else null

    /** Custo estimado do combustível; null sem preço ou sem consumo estimado. */
    fun custo(precoLitro: Double?): Double? = if (precoLitro != null && litros > 0) litros * precoLitro else null

    private val temConsumo: Boolean get() = distanciaKm >= DISTANCIA_MINIMA_CONSUMO_KM && litros > 0

    private fun media(segundos: Double): Double? = if (segundos > 0) distanciaKm / (segundos / 3600.0) else null

    companion object {
        const val DISTANCIA_MINIMA_CONSUMO_KM = 0.1
    }
}

/**
 * Integra as amostras da telemetria nas [EstatisticasPercurso]. Intervalos longos entre leituras
 * contam no máximo [maxIntervaloMs]. Uma velocidade ausente ou impossível (`255`) é substituída pela
 * última válida recente, para não virar distância nem máxima. Não é thread-safe.
 */
class AcumuladorPercurso(private val maxIntervaloMs: Long = ComputadorBordo.MAX_INTERVALO_MS) {

    var estatisticas = EstatisticasPercurso()
        private set

    private var ultimoInstanteMs: Long? = null
    private var velocidadeAnterior: Double? = null
    private var velocidadeAnteriorMs = 0L

    fun registrar(velocidadeKmh: Double?, rpm: Double?, litrosPorHora: Double?, instanteMs: Long): EstatisticasPercurso {
        // Além do filtro do cliente OBD, aqui o 255 nunca entra (vale também nos modos 21/22).
        val lida = velocidadeKmh?.takeIf { it.isFinite() && it >= 0 && it < FiltroVelocidadeEspuria.VALOR_ESPURIO_KMH }
        val anterior = velocidadeAnterior?.takeIf { instanteMs - velocidadeAnteriorMs <= maxIntervaloMs }
        val velocidade = lida ?: anterior
        if (lida != null) {
            velocidadeAnterior = lida
            velocidadeAnteriorMs = instanteMs
        }
        val giro = rpm?.takeIf { it.isFinite() && it >= 0 }
        var e = estatisticas.copy(
            velocidadeMaximaKmh = maiorDe(estatisticas.velocidadeMaximaKmh, lida),
            rpmMaximo = maiorDe(estatisticas.rpmMaximo, giro),
        )
        val antes = ultimoInstanteMs
        if (antes == null || instanteMs > antes) ultimoInstanteMs = instanteMs
        if (antes != null && instanteMs > antes) {
            val segundos = (instanteMs - antes).coerceAtMost(maxIntervaloMs) / 1000.0
            val horas = segundos / 3600.0
            if (velocidade != null) {
                val media = (velocidade + (anterior ?: velocidade)) / 2.0
                val parado = media < VELOCIDADE_PARADO_KMH
                e = e.copy(
                    distanciaKm = e.distanciaKm + if (parado) 0.0 else media * horas,
                    segundosParado = e.segundosParado + if (parado) segundos else 0.0,
                    segundosMovimento = e.segundosMovimento + if (parado) 0.0 else segundos,
                )
            }
            if (giro != null && giro > 0) {
                e = e.copy(rpmSegundos = e.rpmSegundos + giro * segundos, segundosMotor = e.segundosMotor + segundos)
            }
            val vazao = litrosPorHora?.takeIf { it.isFinite() && it > 0 }
            if (vazao != null) e = e.copy(litros = e.litros + vazao * horas)
        }
        estatisticas = e
        return e
    }

    fun zerar() {
        estatisticas = EstatisticasPercurso()
        ultimoInstanteMs = null
        velocidadeAnterior = null
    }

    private fun maiorDe(a: Double?, b: Double?): Double? = if (a == null) b else if (b == null) a else maxOf(a, b)

    private companion object {
        /** O 010D tem resolução de 1 km/h: média abaixo disto é carro parado. */
        const val VELOCIDADE_PARADO_KMH = 0.5
    }
}
