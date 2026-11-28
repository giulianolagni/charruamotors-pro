package com.charruamotors.sistema.gps

import kotlin.math.abs

/** Posição confiável para o log: graus decimais (WGS 84), altitude em m e velocidade em km/h. */
data class PosicaoGps(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val velocidadeKmh: Double? = null,
)

/**
 * Filtro da posição gravada no CSV (puro, testável na JVM), na mesma linha do [FiltroVelocidadeGps]:
 * fix velho, impreciso ou sem coordenadas = null (o campo fica vazio, nunca um ponto repetido de
 * antes de um túnel).
 */
object FiltroPosicaoGps {
    /** Mais folgado que a velocidade: a maioria dos celulares entrega 1 fix por segundo. */
    const val VALIDADE_MS = 3_000L
    private const val ADIANTAMENTO_MAX_MS = 1_000L
    const val PRECISAO_HORIZONTAL_MAX_M = 50f

    /** Coordenadas dentro do globo e diferentes de (0, 0), o "sem posição" de alguns receptores. */
    fun coordenadasValidas(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && abs(latitude) <= 90 && abs(longitude) <= 180 &&
            !(latitude == 0.0 && longitude == 0.0)

    fun posicao(amostra: AmostraGps?, agoraMs: Long): PosicaoGps? {
        val latitude = amostra?.latitude ?: return null
        val longitude = amostra.longitude ?: return null
        if (!coordenadasValidas(latitude, longitude)) return null
        val idade = agoraMs - amostra.instanteMs
        if (idade > VALIDADE_MS || idade < -ADIANTAMENTO_MAX_MS) return null
        if (amostra.precisaoHorizontalM?.let { !it.isFinite() || it > PRECISAO_HORIZONTAL_MAX_M } == true) return null
        return PosicaoGps(
            latitude = latitude,
            longitude = longitude,
            altitudeM = amostra.altitudeM?.takeIf { it.isFinite() },
            velocidadeKmh = FiltroVelocidadeGps.kmh(amostra, agoraMs),
        )
    }
}
