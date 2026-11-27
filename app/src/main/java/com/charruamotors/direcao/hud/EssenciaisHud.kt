package com.charruamotors.direcao.hud

import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.PresetHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.pid.Pids

/** v2.5.0: comandos que o HUD flutuante desenha, para o polling não os atrasar. Puro (JVM). */
object EssenciaisHud {

    private val BAROMETRO = listOf("TOYOTA_BARO", "0133")

    fun comandos(config: ConfigHud): Set<String> = when (config.preset) {
        PresetHud.ESPORTIVO -> buildSet {
            add(Pids.RPM)
            add(Pids.PRESSAO_COLETOR)
            add(Pids.VELOCIDADE)
            addAll(BAROMETRO)
            addAll(CalibradorPedal.COMANDOS)
            addAll(SensorPainel.MARCHA_ALAVANCA.comandos)
            addAll(SensorPainel.MARCHA_ATUAL.comandos)
        }
        PresetHud.PADRAO -> buildSet {
            for (sensor in config.sensoresVisiveis) {
                when (sensor) {
                    SensorPainel.CONSUMO_KML -> {
                        add(Pids.CONSUMO_ECU)
                        add(Pids.VELOCIDADE)
                    }
                    // Sem rail (flex), o HUD mostra a bateria no lugar.
                    SensorPainel.RAIL -> addAll(sensor.comandos + Pids.TENSAO_BATERIA)
                    else -> addAll(sensor.comandos)
                }
            }
        }
    }
}
