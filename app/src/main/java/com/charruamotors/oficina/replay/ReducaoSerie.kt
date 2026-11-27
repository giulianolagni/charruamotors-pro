package com.charruamotors.oficina.replay

/** Um ponto do traço reduzido: a amostra [indice]; [novoTraco] = não ligar ao ponto anterior. */
data class PontoReduzido(val indice: Int, val novoTraco: Boolean)

/**
 * Downsampling das séries para o gráfico (v2.5.0): com mais amostras que pixels, cada coluna fica só
 * com o mínimo e o máximo dela, na ordem em que aconteceram — picos não somem no zoom afastado.
 * Trechos sem leitura (`NaN`) interrompem o traço. Puro (JVM).
 */
object ReducaoSerie {

    /** Até este múltiplo da largura em pixels as amostras vão todas, sem redução. */
    const val AMOSTRAS_POR_COLUNA_SEM_REDUCAO = 2

    /**
     * @param de primeiro índice desenhado (inclusive) e [ate] o último (inclusive).
     * @param janela trecho de tempo que ocupa as [colunas] (pixels) do gráfico.
     */
    fun reduzir(instantes: LongArray, valores: DoubleArray, de: Int, ate: Int, janela: JanelaReplay, colunas: Int): List<PontoReduzido> {
        val inicio = de.coerceAtLeast(0)
        val fim = ate.coerceAtMost(minOf(instantes.size, valores.size) - 1)
        if (fim < inicio) return emptyList()
        val largura = colunas.coerceAtLeast(1)
        val saida = ArrayList<PontoReduzido>()
        var interrompido = true

        fun emitir(indice: Int) {
            saida += PontoReduzido(indice, interrompido)
            interrompido = false
        }

        if (fim - inicio + 1 <= largura * AMOSTRAS_POR_COLUNA_SEM_REDUCAO) {
            for (i in inicio..fim) if (valores[i].isNaN()) interrompido = true else emitir(i)
            return saida
        }

        val duracao = janela.duracaoMs.coerceAtLeast(1).toDouble()
        var coluna = Long.MIN_VALUE
        var minimo = -1
        var maximo = -1

        fun fecharColuna() {
            if (coluna == Long.MIN_VALUE) return
            if (minimo < 0) {
                interrompido = true // a coluna inteira veio sem leitura
                return
            }
            emitir(minOf(minimo, maximo))
            if (minimo != maximo) emitir(maxOf(minimo, maximo))
        }

        for (i in inicio..fim) {
            val c = Math.floor((instantes[i] - janela.inicioMs) / duracao * largura).toLong()
            if (c != coluna) {
                fecharColuna()
                coluna = c
                minimo = -1
                maximo = -1
            }
            val v = valores[i]
            if (v.isNaN()) continue
            if (minimo < 0 || v < valores[minimo]) minimo = i
            if (maximo < 0 || v > valores[maximo]) maximo = i
        }
        fecharColuna()
        return saida
    }
}
