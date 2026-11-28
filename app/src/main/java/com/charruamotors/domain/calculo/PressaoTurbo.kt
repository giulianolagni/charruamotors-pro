package com.charruamotors.domain.calculo

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids

/** De onde saiu o barômetro usado no boost, na ordem de prioridade. */
enum class FonteBarometro { ECU, MAP_PARADO, PADRAO }

data class Barometro(val kpa: Double, val fonte: FonteBarometro)

/**
 * Pressão relativa do turbo ([Pids.PRESSAO_BOOST] = MAP − barômetro, em kPa), calculada só com o
 * que a sessão já lê: nenhum comando OBD2 novo. O barômetro é (a) o da ECU (`2133` Toyota ou
 * `0133`), (b) a média inicial do MAP com a ignição ligada e o motor parado ou (c)
 * [PADRAO_KPA]. Uma instância por sessão ([reiniciar]). Puro (JVM).
 */
class CalculadoraBoost {
    private var barometroEcu: Double? = null
    private var somaMapParado = 0.0
    private var amostrasParado = 0
    private var motorJaGirou = false

    fun reiniciar() {
        barometroEcu = null
        somaMapParado = 0.0
        amostrasParado = 0
        motorJaGirou = false
    }

    val barometro: Barometro
        get() = barometroEcu?.let { Barometro(it, FonteBarometro.ECU) }
            ?: if (amostrasParado > 0) {
                Barometro(somaMapParado / amostrasParado, FonteBarometro.MAP_PARADO)
            } else {
                Barometro(PADRAO_KPA, FonteBarometro.PADRAO)
            }

    /**
     * Mescla o boost ao instantâneo; sem MAP, ele segue igual. No [diesel] (sem borboleta fechando o
     * coletor) o ruído entre −0,05 bar e zero vira `0,0`; no ciclo Otto o vácuo é mantido.
     */
    fun aplicar(dados: DadosObd, diesel: Boolean): DadosObd {
        COMANDOS_BAROMETRO.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() && it in FAIXA_BAROMETRO_KPA } }
            ?.let { barometroEcu = it }
        val map = dados[Pids.PRESSAO_COLETOR]?.takeIf { it.isFinite() }
        val rpm = dados[Pids.RPM]?.takeIf { it.isFinite() }
        if (rpm != null && rpm > 0) motorJaGirou = true
        // Só antes da primeira partida: logo após desligar, o coletor ainda não voltou à atmosfera.
        if (rpm == 0.0 && !motorJaGirou && map != null && map in FAIXA_BAROMETRO_KPA && amostrasParado < AMOSTRAS_PARADO) {
            somaMapParado += map
            amostrasParado++
        }
        if (map == null) return dados
        return DadosObd(dados.valores + (Pids.PRESSAO_BOOST to boostKpa(map, barometro.kpa, diesel)))
    }

    companion object {
        const val PADRAO_KPA = 101.3

        /** Micro-negativo tolerado no diesel: −0,05 bar. */
        const val ZONA_MORTA_DIESEL_KPA = 5.0

        /** Leituras que formam a média inicial do MAP com o motor parado. */
        const val AMOSTRAS_PARADO = 20

        val COMANDOS_BAROMETRO = listOf("TOYOTA_BARO", "0133")
        val FAIXA_BAROMETRO_KPA = 50.0..115.0

        fun boostKpa(mapKpa: Double, baroKpa: Double, diesel: Boolean): Double {
            val boost = mapKpa - baroKpa
            return if (diesel && boost < 0 && boost >= -ZONA_MORTA_DIESEL_KPA) 0.0 else boost
        }
    }
}
