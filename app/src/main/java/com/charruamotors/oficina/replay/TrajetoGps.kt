package com.charruamotors.oficina.replay

import com.charruamotors.obd.log.ColunasCelular
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.gps.FiltroPosicaoGps
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/** Uma posição válida do log: [indice] é o da amostra (o mesmo do cursor do gráfico). */
data class PontoTrajeto(
    val indice: Int,
    val instanteMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
)

/** Onde o carro estava no maior valor da coluna [coluna] do log. */
data class PicoTrajeto(val comando: String, val coluna: Int, val indice: Int, val ponto: PontoTrajeto)

/**
 * Trajeto de um log CSV com as colunas `gps_lat`/`gps_lon` (v2.12.0): as posições válidas em ordem
 * de tempo, ligadas às amostras do gráfico pelo índice. Puro (JVM).
 */
class TrajetoGps(val pontos: List<PontoTrajeto>, private val instantesMs: LongArray) {

    private val instantesDosPontos = LongArray(pontos.size) { pontos[it].instanteMs }
    private val porIndice = pontos.associateBy { it.indice }

    /**
     * Posição da amostra [indice]: a da própria linha ou, sem fix nela, a mais próxima no tempo a até
     * [TOLERANCIA_MS]. Null fora do trajeto (túnel, GPS ainda sem sinal).
     */
    fun pontoEm(indice: Int): PontoTrajeto? {
        porIndice[indice]?.let { return it }
        val instante = instantesMs.getOrNull(indice) ?: return null
        val i = AnaliseReplay.indiceMaisProximo(instantesDosPontos, instante)
        return pontos.getOrNull(i)?.takeIf { abs(it.instanteMs - instante) <= TOLERANCIA_MS }
    }

    /** Trechos contínuos: uma lacuna maior que [LACUNA_TRECHO_MS] interrompe a linha. */
    val trechos: List<List<PontoTrajeto>> by lazy {
        val resultado = mutableListOf<MutableList<PontoTrajeto>>()
        for (p in pontos) {
            val atual = resultado.lastOrNull()
            if (atual == null || p.instanteMs - atual.last().instanteMs > LACUNA_TRECHO_MS) {
                resultado.add(mutableListOf(p))
            } else {
                atual.add(p)
            }
        }
        resultado
    }

    /**
     * Pico (o primeiro maior valor do log inteiro) de cada comando de [comandos] que tenha posição
     * conhecida naquele instante.
     */
    fun picos(log: LogCsv, comandos: List<String> = COMANDOS_PICO): List<PicoTrajeto> = comandos.mapNotNull { comando ->
        val coluna = log.colunas.indexOfFirst { it.comando == comando }
        val serie = log.series.getOrNull(coluna) ?: return@mapNotNull null
        var melhor = -1
        for (i in serie.indices) if (serie[i].isFinite() && (melhor < 0 || serie[i] > serie[melhor])) melhor = i
        if (melhor < 0) return@mapNotNull null
        val ponto = pontoEm(melhor) ?: return@mapNotNull null
        PicoTrajeto(comando, coluna, melhor, ponto)
    }

    companion object {
        const val TOLERANCIA_MS = 5_000L
        const val LACUNA_TRECHO_MS = 30_000L

        /** RPM, MAP/turbo e pressão do rail. */
        val COMANDOS_PICO = listOf(Pids.RPM, Pids.PRESSAO_COLETOR, Pids.PRESSAO_RAIL)

        /** O trajeto do [log]; null sem as colunas de posição ou sem nenhuma coordenada válida. */
        fun de(log: LogCsv): TrajetoGps? {
            fun serie(comando: String) = log.colunas.indexOfFirst { it.comando == comando }.takeIf { it >= 0 }?.let { log.series[it] }
            val latitudes = serie(ColunasCelular.GPS_LATITUDE) ?: return null
            val longitudes = serie(ColunasCelular.GPS_LONGITUDE) ?: return null
            val altitudes = serie(ColunasCelular.GPS_ALTITUDE)
            val pontos = (0 until log.amostras).mapNotNull { i ->
                if (!FiltroPosicaoGps.coordenadasValidas(latitudes[i], longitudes[i])) return@mapNotNull null
                PontoTrajeto(i, log.instantesMs[i], latitudes[i], longitudes[i], altitudes?.get(i)?.takeIf { it.isFinite() })
            }
            return if (pontos.isEmpty()) null else TrajetoGps(pontos, log.instantesMs)
        }
    }
}

/**
 * Projeção plana (equiretangular, suficiente na escala de uma viagem) do trajeto num quadro de
 * [largura] × [altura], com o norte para cima, a mesma escala nos dois eixos e centralizado.
 */
class ProjecaoTrajeto(pontos: List<PontoTrajeto>, largura: Float, altura: Float, margem: Float) {
    private val latMin = pontos.minOfOrNull { it.latitude } ?: 0.0
    private val latMax = pontos.maxOfOrNull { it.latitude } ?: 0.0
    private val lonMin = pontos.minOfOrNull { it.longitude } ?: 0.0
    private val lonMax = pontos.maxOfOrNull { it.longitude } ?: 0.0

    /** Um grau de longitude encolhe com a latitude. */
    private val fatorLon = cos(Math.toRadians((latMin + latMax) / 2))
    private val larguraGraus = (lonMax - lonMin) * fatorLon
    private val alturaGraus = latMax - latMin
    private val escala: Double = run {
        val util = (largura - 2 * margem).coerceAtLeast(1f).toDouble()
        val alto = (altura - 2 * margem).coerceAtLeast(1f).toDouble()
        // Carro parado: um mínimo de ~50 m evita ampliar o ruído do GPS até a tela inteira.
        min(util / larguraGraus.coerceAtLeast(EXTENSAO_MINIMA_GRAUS), alto / alturaGraus.coerceAtLeast(EXTENSAO_MINIMA_GRAUS))
    }
    private val deslocamentoX = (largura - larguraGraus * escala) / 2
    private val deslocamentoY = (altura - alturaGraus * escala) / 2

    fun x(p: PontoTrajeto): Float = (deslocamentoX + (p.longitude - lonMin) * fatorLon * escala).toFloat()

    fun y(p: PontoTrajeto): Float = (deslocamentoY + (latMax - p.latitude) * escala).toFloat()

    private companion object {
        const val EXTENSAO_MINIMA_GRAUS = 0.0005
    }
}
