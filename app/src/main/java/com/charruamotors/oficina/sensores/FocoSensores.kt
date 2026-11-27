package com.charruamotors.oficina.sensores

import com.charruamotors.domain.calculo.AlavancaGmTcm
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.obd.FocoPolling
import com.charruamotors.obd.pid.Pids

/**
 * Monta o [FocoPolling] das telas de inspeção (v2.5.0): os sensores visíveis (com as entradas dos
 * calculados), o essencial do HUD e dos alertas críticos e o apoio do computador de bordo. Puro (JVM).
 */
object FocoSensores {

    private const val ALAVANCA_NO_MOTOR = "TOYOTA_ALAVANCA_ECM"

    /** Um sensor calculado pelo app não é lido da ECU: priorizá-lo é priorizar as entradas dele. */
    fun expandir(comandos: Collection<String>): Set<String> = buildSet {
        for (comando in comandos) {
            add(comando)
            when (comando) {
                Pids.RELACAO_AF_REAL -> {
                    addAll(Pids.COMANDOS_LAMBDA_MEDIDO)
                    add(Pids.LAMBDA_COMANDADO)
                    add(Pids.PERCENTUAL_ETANOL)
                }
                // A marcha estimada sai da razão rpm/velocidade e da alavanca.
                CambioA750f.CHAVE_MARCHA -> {
                    add(Pids.RPM)
                    add(Pids.VELOCIDADE)
                    add(CambioA750f.CHAVE_ALAVANCA)
                    add(ALAVANCA_NO_MOTOR)
                }
                AlavancaGmTcm.CHAVE_POSICAO -> addAll(AlavancaGmTcm.CHAVES_SELETOR)
            }
        }
    }

    /**
     * @param visiveis o que as telas abertas estão desenhando.
     * @param alertas chaves das regras de alerta ligadas no veículo.
     * @param hud sensores do HUD flutuante; vazio com ele desligado.
     * @param suportados o que a ECU suporta (decide as entradas do consumo).
     */
    fun montar(visiveis: Collection<String>, alertas: Set<String>, hud: Set<String>, suportados: Set<String>): FocoPolling {
        val consumo = if (Pids.MAF in suportados) {
            setOf(Pids.MAF)
        } else {
            setOf(Pids.PRESSAO_COLETOR, Pids.TEMPERATURA_AR_ADMISSAO) // speed-density
        }
        return FocoPolling(
            visiveis = expandir(visiveis),
            essenciais = expandir(alertas + hud + Pids.RPM + Pids.VELOCIDADE),
            apoio = consumo + Pids.CONSUMO_ECU,
        )
    }
}
