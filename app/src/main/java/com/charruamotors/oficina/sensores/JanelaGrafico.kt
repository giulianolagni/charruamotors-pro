package com.charruamotors.oficina.sensores

import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.oficina.replay.EstatisticaSerie
import com.charruamotors.oficina.replay.JanelaReplay

/** Janela deslizante dos Gráficos em Tempo Real. */
enum class JanelaTempo(val segundos: Int) {
    S30(30),
    M2(120),
    M5(300),
    ;

    val ms: Long get() = segundos * 1_000L

    companion object {
        val PADRAO = M2
    }
}

/** Leitura de todos os canais num mesmo instante (o cursor de toque). */
data class LeituraCursor(val tMs: Long, val valores: Map<String, Double?>)

/**
 * O que o gráfico ao vivo desenha: a [janela] de tempo e uma série por comando, todas alinhadas aos
 * [instantes] (`NaN` = amostra sem aquele sensor). Mesmo formato do replay de CSV.
 */
class QuadroGrafico(
    val janela: JanelaReplay,
    val comandos: List<String>,
    val instantes: LongArray,
    val series: List<DoubleArray>,
) {
    val vazio: Boolean get() = instantes.isEmpty()

    /** Valor da amostra mais nova da janela; null se ela veio sem este sensor. */
    fun atual(canal: Int): Double? = series[canal].lastOrNull()?.takeUnless { it.isNaN() }

    /** Mínimo / máximo / média do canal dentro da janela; null sem leituras. */
    fun estatistica(canal: Int): EstatisticaSerie? = AnaliseReplay.estatisticas(series[canal], instantes.indices)

    /** Os valores de todos os canais na amostra [indice]; null fora das amostras. */
    fun leitura(indice: Int): LeituraCursor? {
        val t = instantes.getOrNull(indice) ?: return null
        return LeituraCursor(t, comandos.indices.associate { c -> comandos[c] to series[c][indice].takeUnless { it.isNaN() } })
    }
}

/** Recorta o buffer da sessão na janela deslizante. Puro (JVM). */
object JanelaDeslizante {

    /**
     * @param amostras em ordem cronológica.
     * @param fimMs borda direita (a última amostra ao vivo, ou a do momento da pausa).
     * @param desdeMs corte do "Zerar": nada anterior a ele aparece nem entra nas estatísticas.
     */
    fun recortar(amostras: List<Amostra>, comandos: List<String>, fimMs: Long, janela: JanelaTempo, desdeMs: Long = 0): QuadroGrafico {
        val inicio = fimMs - janela.ms
        val corte = maxOf(inicio, desdeMs)
        val de = primeiraEm(amostras, corte)
        val ate = primeiraEm(amostras, fimMs + 1)
        val n = (ate - de).coerceAtLeast(0)
        val instantes = LongArray(n) { amostras[de + it].tMs }
        val series = comandos.map { c -> DoubleArray(n) { amostras[de + it].valores[c] ?: Double.NaN } }
        return QuadroGrafico(JanelaReplay(inicio, fimMs), comandos, instantes, series)
    }

    /** Busca binária: índice da primeira amostra com `tMs` ≥ [alvoMs]. */
    private fun primeiraEm(amostras: List<Amostra>, alvoMs: Long): Int {
        var de = 0
        var ate = amostras.size
        while (de < ate) {
            val meio = (de + ate) ushr 1
            if (amostras[meio].tMs < alvoMs) de = meio + 1 else ate = meio
        }
        return de
    }
}

/** Quais sensores o gráfico mostra: os salvos no perfil do veículo ou a sugestão. Puro (JVM). */
object SelecaoGrafico {
    const val MAXIMO = 4

    /** Diesel (SW4): RPM + Turbo + Rail. */
    val SUGESTAO_DIESEL = listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.PRESSAO_RAIL)

    /** Flex/gasolina (Cruze, Onix): RPM + MAP + STFT + LTFT. */
    val SUGESTAO_OTTO = listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.STFT, Pids.LTFT)

    /** @param diesel null = combustível desconhecido: decide pela presença do rail. */
    fun sugestao(disponiveis: Collection<String>, diesel: Boolean?): List<String> {
        val ehDiesel = diesel ?: (Pids.PRESSAO_RAIL in disponiveis)
        val base = (if (ehDiesel) SUGESTAO_DIESEL else SUGESTAO_OTTO).filter { it in disponiveis }
        return base.ifEmpty { disponiveis.take(2) }
    }

    /** Os [salvos] que esta sessão tem; nenhum deles = a [sugestao]. */
    fun efetiva(salvos: List<String>, disponiveis: Collection<String>, diesel: Boolean?): List<String> =
        salvos.filter { it in disponiveis }.distinct().take(MAXIMO).ifEmpty { sugestao(disponiveis, diesel) }
}
