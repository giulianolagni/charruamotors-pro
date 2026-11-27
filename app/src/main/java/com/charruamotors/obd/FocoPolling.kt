package com.charruamotors.obd

/**
 * v2.5.0: o que o polling deve priorizar enquanto uma tela de inspeção (Todos os Sensores ou Gráficos
 * em Tempo Real) está aberta. Só reordena e restringe os PIDs já suportados: um ciclo com foco nunca
 * envia mais comandos que o ciclo normal (ver [EscalonadorPolling]).
 *
 * @property visiveis sensores desenhados na tela agora.
 * @property essenciais o que não pode parar: sensores do HUD flutuante ativo, dos alertas críticos,
 *   RPM e velocidade. Os da fila rápida continuam em todo ciclo.
 * @property apoio entradas dos cálculos do app (consumo): bastam na vez da fila lenta.
 */
data class FocoPolling(
    val visiveis: Set<String>,
    val essenciais: Set<String> = emptySet(),
    val apoio: Set<String> = emptySet(),
)
