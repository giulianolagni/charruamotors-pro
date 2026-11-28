package com.charruamotors.oficina.replay

import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.sistema.unidades.ConversorUnidades

/**
 * v2.11.2: o log convertido para as unidades das Configurações, só para exibição (o CSV continua em
 * SI). Convertendo as séries na abertura, chips, escalas, cursor e mín/máx/média saem todos na mesma
 * unidade. O rail segue a regra do Painel (bar ou psi). Puro (JVM).
 */
fun LogCsv.emUnidades(conversor: ConversorUnidades): LogCsv {
    val destinos = colunas.map { SensorPainel.exibirDoComando(it.comando, 0.0, it.unidade, 0, conversor).unidade }
    return LogCsv(
        colunas = colunas.mapIndexed { i, c -> c.copy(unidade = destinos[i]) },
        instantesMs = instantesMs,
        series = series.mapIndexed { i, serie ->
            val coluna = colunas[i]
            if (destinos[i] == coluna.unidade) {
                serie
            } else {
                DoubleArray(serie.size) { SensorPainel.exibirDoComando(coluna.comando, serie[it], coluna.unidade, 0, conversor).valor }
            }
        },
        tempoAbsoluto = tempoAbsoluto,
        linhasIgnoradas = linhasIgnoradas,
    )
}
