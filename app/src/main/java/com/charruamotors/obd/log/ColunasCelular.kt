package com.charruamotors.obd.log

import com.charruamotors.direcao.forcag.ForcaG
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Formula
import com.charruamotors.sistema.gps.PosicaoGps

/** O que o usuário pediu para gravar dos sensores do próprio celular (Configurações ou atalho do log). */
data class OpcoesLogCelular(val posicaoGps: Boolean = false, val forcaG: Boolean = false)

/**
 * Colunas opcionais do CSV que vêm do celular, não da ECU (v2.12.0). O cabeçalho no arquivo é o
 * nome curto (`gps_lat`, `forca_g_long`...), sem o formato `Nome (unidade) [comando]`; dentro do app
 * cada uma tem um comando próprio. CSVs sem elas continuam sendo lidos como sempre.
 */
object ColunasCelular {
    const val GPS_LATITUDE = "GPS_LAT"
    const val GPS_LONGITUDE = "GPS_LON"
    const val GPS_ALTITUDE = "GPS_ALT_M"
    const val GPS_VELOCIDADE = "GPS_VEL_KMH"
    const val FORCA_G_LONGITUDINAL = "FORCA_G_LONG"
    const val FORCA_G_LATERAL = "FORCA_G_LAT"

    /** Onde o carro esteve: fora do resumo estatístico, do gráfico ao vivo e dos chips do replay. */
    val COORDENADAS: Set<String> = setOf(GPS_LATITUDE, GPS_LONGITUDE)

    private val GPS: List<DefinicaoPid> by lazy {
        listOf(
            coluna(GPS_LATITUDE, "gps_lat", "Latitude GPS", "°", -90.0, 90.0, 6),
            coluna(GPS_LONGITUDE, "gps_lon", "Longitude GPS", "°", -180.0, 180.0, 6),
            coluna(GPS_ALTITUDE, "gps_alt_m", "Altitude GPS", "m", -500.0, 9_000.0, 1),
            coluna(GPS_VELOCIDADE, "gps_vel_kmh", "Velocidade GPS (posição)", "km/h", 0.0, 400.0, 1),
        )
    }

    private val FORCA_G: List<DefinicaoPid> by lazy {
        listOf(
            coluna(FORCA_G_LONGITUDINAL, "forca_g_long", "Força G longitudinal", "g", -10.0, 10.0, 3),
            coluna(FORCA_G_LATERAL, "forca_g_lat", "Força G lateral", "g", -10.0, 10.0, 3),
        )
    }

    private val todas: List<DefinicaoPid> by lazy { GPS + FORCA_G }

    /** Todos os comandos destas colunas. */
    val COMANDOS: Set<String> by lazy { todas.map { it.comando }.toSet() }

    fun definicoes(opcoes: OpcoesLogCelular): List<DefinicaoPid> =
        (if (opcoes.posicaoGps) GPS else emptyList()) + (if (opcoes.forcaG) FORCA_G else emptyList())

    /** A definição de uma coluna lida de um CSV pelo texto do cabeçalho; null = não é uma delas. */
    fun doCabecalho(texto: String): DefinicaoPid? = todas.firstOrNull { it.cabecalhoCsv.equals(texto, ignoreCase = true) }

    /** Leituras do instante para as colunas pedidas; sem fix ou sem calibração a chave fica ausente. */
    fun valores(opcoes: OpcoesLogCelular, posicao: PosicaoGps?, forca: ForcaG?): Map<String, Double> = buildMap {
        if (opcoes.posicaoGps && posicao != null) {
            put(GPS_LATITUDE, posicao.latitude)
            put(GPS_LONGITUDE, posicao.longitude)
            posicao.altitudeM?.let { put(GPS_ALTITUDE, it) }
            posicao.velocidadeKmh?.let { put(GPS_VELOCIDADE, it) }
        }
        if (opcoes.forcaG && forca != null) {
            put(FORCA_G_LONGITUDINAL, forca.longitudinal)
            put(FORCA_G_LATERAL, forca.lateral)
        }
    }

    private fun coluna(comando: String, cabecalho: String, nome: String, unidade: String, min: Double, max: Double, casas: Int) =
        DefinicaoPid(
            comando = comando,
            nome = nome,
            unidade = unidade,
            bytes = 0,
            formula = Formula.compilar("0"),
            min = min,
            max = max,
            casasDecimais = casas,
            doAparelho = true,
            cabecalhoCsv = cabecalho,
        )
}
