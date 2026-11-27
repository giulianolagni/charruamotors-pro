package com.charruamotors.obd

import com.charruamotors.obd.pid.Pids

/**
 * Polling prioritário do teste de aceleração: cada ciclo lê só [comandos] (mais os [urgentes] e, de
 * vez em quando, um dos [vigiados]). Diferente do [FocoPolling], aqui o resto **deixa** de ser lido
 * enquanto durar, então quem liga é responsável por desligar (ver `ControladorTesteAceleracao`).
 *
 * @property comandos lidos em todo ciclo, nesta ordem; o último fica mais perto do instante da amostra.
 * @property vigiados lidos um por vez, em rodízio, 1 vez a cada [ciclosPorVigiado] ciclos.
 * @property ciclosPorVigiado 0 = nenhum vigiado.
 * @property urgentes alertas em confirmação: lidos em todo ciclo, mesmo durante a medição.
 */
data class PrioridadePolling(
    val comandos: List<String>,
    val vigiados: List<String> = emptyList(),
    val ciclosPorVigiado: Int = 0,
    val urgentes: List<String> = emptyList(),
) {
    companion object {
        /** RPM e, por último, a velocidade que alimenta o cronômetro. */
        val ACELERACAO: List<String> = listOf(Pids.RPM, Pids.VELOCIDADE)
    }
}
