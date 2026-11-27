package com.charruamotors.oficina.injecao

/** Tipo da sonda pré-catalisador: tensão (0,1–0,9 V) ou banda larga (λ). */
enum class TipoSondaPre { ESTREITA, BANDA_LARGA }

enum class SituacaoSondaPre(val texto: String, val gravidade: Int?) {
    COLETANDO("Coletando leituras com o motor quente…", null),
    OSCILANDO("Oscilando normalmente", 0),
    TRAVADA_RICA("Travada no rico: sonda travada/lenta ou mistura rica fora de faixa", 2),
    TRAVADA_POBRE("Travada no pobre: sonda travada/lenta ou mistura pobre fora de faixa", 2),
    TRAVADA_MEIO("Parada no meio da faixa: sonda travada/lenta", 2),
    OSCILACAO_FRACA("Oscilação fraca: repetir com o motor em lenta estável", null),
    LAMBDA_NORMAL("Mistura em torno de λ 1,00", 0),
    LAMBDA_RICA("Mistura rica fora de faixa (λ < 0,95)", 1),
    LAMBDA_POBRE("Mistura pobre fora de faixa (λ > 1,05)", 1),
}

enum class SituacaoCatalisador(val texto: String, val gravidade: Int?) {
    COLETANDO("Coletando a sonda pós-catalisador…", null),
    NORMAL("Pós-catalisador estável: catalisador normal", 0),
    ESPELHANDO("Pós-catalisador espelha a oscilação: possível baixa eficiência do catalisador", 2),
    FORA_DO_USUAL("Pós-catalisador fora da faixa usual (0,45–0,75 V): sem conclusão", null),
}

/** Por que a análise não está rodando. */
enum class BloqueioSondas(val texto: String) {
    MOTOR_FRIO("Aguardando o motor aquecer (água ≥ 75 °C)"),
    MOTOR_DESLIGADO("Ligue o motor para analisar as sondas"),
    DIESEL("A heurística de sondas não se aplica a motor diesel"),
    SEM_SONDA("Não suportado neste veículo"),
}

data class DiagnosticoSondas(
    val bloqueio: BloqueioSondas? = null,
    val pre: SituacaoSondaPre? = null,
    val catalisador: SituacaoCatalisador? = null,
) {
    /** Há uma conclusão sobre alguma sonda (vai para o laudo). */
    val conclusivo: Boolean get() = pre?.gravidade != null || catalisador?.gravidade != null

    val texto: String
        get() = bloqueio?.texto ?: listOfNotNull(pre?.let { "S1: ${it.texto}" }, catalisador?.let { "Catalisador: ${it.texto}" })
            .joinToString("\n")
}

/**
 * Heurística objetiva das sondas O2 sobre uma janela das leituras com o motor aquecido. Pura (JVM) e
 * não thread-safe. As leituras de S1 e S2 não precisam ser simultâneas: contam a amplitude e os
 * cruzamentos de 0,45 V de cada uma.
 */
class AnaliseSondas(private val tipo: TipoSondaPre?, private val temPos: Boolean, private val diesel: Boolean) {

    private val pre = ArrayDeque<Pair<Long, Double>>()
    private val pos = ArrayDeque<Pair<Long, Double>>()

    /**
     * @param s1 leitura **nova** da pré-catalisador (V ou λ); null = não lida neste ciclo.
     * @param s2 idem para a pós-catalisador (V).
     */
    fun registrar(agoraMs: Long, agua: Double?, rpm: Double?, s1: Double?, s2: Double?): DiagnosticoSondas {
        bloqueio(agua, rpm)?.let {
            // Leituras frias ou com o motor parado não contam: a janela recomeça.
            pre.clear()
            pos.clear()
            return DiagnosticoSondas(bloqueio = it)
        }
        s1?.let { pre.addLast(agoraMs to it) }
        s2?.let { pos.addLast(agoraMs to it) }
        aparar(pre, agoraMs)
        aparar(pos, agoraMs)
        val situacaoPre = avaliarPre()
        return DiagnosticoSondas(pre = situacaoPre, catalisador = if (temPos) avaliarCatalisador(situacaoPre) else null)
    }

    private fun bloqueio(agua: Double?, rpm: Double?): BloqueioSondas? = when {
        tipo == null -> BloqueioSondas.SEM_SONDA
        diesel -> BloqueioSondas.DIESEL
        rpm != null && rpm <= AnaliseMistura.RPM_MOTOR_LIGADO -> BloqueioSondas.MOTOR_DESLIGADO
        agua == null || agua < AGUA_MINIMA -> BloqueioSondas.MOTOR_FRIO
        else -> null
    }

    private fun aparar(fila: ArrayDeque<Pair<Long, Double>>, agoraMs: Long) {
        while (fila.isNotEmpty() && agoraMs - fila.first().first > JANELA_MS) fila.removeFirst()
    }

    private fun suficiente(fila: ArrayDeque<Pair<Long, Double>>) =
        fila.size >= MIN_AMOSTRAS && fila.last().first - fila.first().first >= DURACAO_MINIMA_MS

    private fun avaliarPre(): SituacaoSondaPre {
        if (!suficiente(pre)) return SituacaoSondaPre.COLETANDO
        val v = pre.map { it.second }
        if (tipo == TipoSondaPre.BANDA_LARGA) {
            return when {
                v.count { it < LAMBDA_MINIMO } >= v.size * FRACAO_FORA -> SituacaoSondaPre.LAMBDA_RICA
                v.count { it > LAMBDA_MAXIMO } >= v.size * FRACAO_FORA -> SituacaoSondaPre.LAMBDA_POBRE
                else -> SituacaoSondaPre.LAMBDA_NORMAL
            }
        }
        if (oscila(v)) return SituacaoSondaPre.OSCILANDO
        if (cruzamentos(v) > 1) return SituacaoSondaPre.OSCILACAO_FRACA
        val media = v.average()
        return when {
            media >= TENSAO_ALTA -> SituacaoSondaPre.TRAVADA_RICA
            media <= TENSAO_BAIXA -> SituacaoSondaPre.TRAVADA_POBRE
            else -> SituacaoSondaPre.TRAVADA_MEIO
        }
    }

    private fun avaliarCatalisador(situacaoPre: SituacaoSondaPre): SituacaoCatalisador {
        if (!suficiente(pos)) return SituacaoCatalisador.COLETANDO
        val v = pos.map { it.second }
        // Só dá para falar em espelhamento com a pré-catalisador trabalhando.
        val preAtiva = situacaoPre == SituacaoSondaPre.OSCILANDO || situacaoPre == SituacaoSondaPre.LAMBDA_NORMAL
        if (oscila(v)) {
            val comparavel = tipo == TipoSondaPre.BANDA_LARGA ||
                cruzamentos(v) >= cruzamentos(pre.map { it.second }) * FRACAO_ESPELHO
            return if (preAtiva && comparavel) SituacaoCatalisador.ESPELHANDO else SituacaoCatalisador.FORA_DO_USUAL
        }
        val estavel = v.count { it in FAIXA_POS } >= v.size * FRACAO_FORA && v.max() - v.min() <= AMPLITUDE_ESTAVEL
        return if (estavel && preAtiva) SituacaoCatalisador.NORMAL else SituacaoCatalisador.FORA_DO_USUAL
    }

    private fun oscila(v: List<Double>) = v.min() <= TENSAO_BAIXA && v.max() >= TENSAO_ALTA && cruzamentos(v) >= MIN_CRUZAMENTOS

    private fun cruzamentos(v: List<Double>) = v.zipWithNext().count { (a, b) -> (a < MEIO) != (b < MEIO) }

    companion object {
        const val AGUA_MINIMA = 75.0
        const val JANELA_MS = 30_000L
        const val DURACAO_MINIMA_MS = 20_000L
        const val MIN_AMOSTRAS = 12

        private const val MEIO = 0.45
        private const val TENSAO_BAIXA = 0.3
        private const val TENSAO_ALTA = 0.6
        private const val MIN_CRUZAMENTOS = 3
        private val FAIXA_POS = 0.45..0.75
        private const val AMPLITUDE_ESTAVEL = 0.3
        private const val FRACAO_FORA = 0.9
        private const val FRACAO_ESPELHO = 0.5
        private const val LAMBDA_MINIMO = 0.95
        private const val LAMBDA_MAXIMO = 1.05
    }
}
