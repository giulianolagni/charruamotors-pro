package com.charruamotors.direcao.forcag

/**
 * Passa-baixa de primeira ordem pelo tempo decorrido (não depende da taxa do sensor): a cada passo a
 * saída anda `dt / (constante + dt)` em direção à entrada.
 */
class PassaBaixa(private val constanteS: Double) {
    private var saida: Vetor3? = null
    private var ultimoNs = 0L

    fun aplicar(entrada: Vetor3, instanteNs: Long): Vetor3 {
        val anterior = saida
        val dt = (instanteNs - ultimoNs) / 1e9
        ultimoNs = instanteNs
        // Primeira leitura, ou um buraco grande (sensor parado): começa do valor atual.
        val nova = if (anterior == null || dt < 0 || dt > LACUNA_MAXIMA_S) {
            entrada
        } else {
            anterior + (entrada - anterior) * (dt / (constanteS + dt))
        }
        saida = nova
        return nova
    }

    fun reiniciar() {
        saida = null
    }

    private companion object {
        const val LACUNA_MAXIMA_S = 1.0
    }
}

/**
 * Fallback sem `TYPE_LINEAR_ACCELERATION`: estima a gravidade com um passa-baixa lento sobre o
 * acelerômetro e a subtrai. A constante é um compromisso: acelerações mantidas por vários segundos
 * (frenagem longa, curva de rotatória) vão sendo absorvidas e aparecem menores.
 */
class FiltroGravidade(constanteS: Double = CONSTANTE_S) {
    private val gravidade = PassaBaixa(constanteS)

    fun remover(acelerometro: Vetor3, instanteNs: Long): Vetor3 = acelerometro - gravidade.aplicar(acelerometro, instanteNs)

    fun reiniciar() = gravidade.reiniciar()

    companion object {
        const val CONSTANTE_S = 3.0
    }
}

/**
 * Entrega uma amostra (a média das recebidas) a cada [periodoNs], qualquer que seja a taxa real do
 * sensor — o Android trata o período pedido só como sugestão.
 */
class AmostradorEstavel(private val periodoNs: Long = PERIODO_NS) {
    private var soma = Vetor3.ZERO
    private var n = 0
    private var proximoNs = 0L
    private var iniciado = false

    /** A média do período que acabou de fechar, ou null enquanto ele está aberto. */
    fun adicionar(v: Vetor3, instanteNs: Long): Vetor3? {
        if (!iniciado || instanteNs - proximoNs > periodoNs * 10 || proximoNs - instanteNs > periodoNs * 10) {
            reiniciar()
            iniciado = true
            proximoNs = instanteNs + periodoNs
        }
        soma += v
        n++
        // Meio milissegundo de folga: um sensor exatamente no período não perde a janela pelo jitter.
        if (instanteNs < proximoNs - FOLGA_NS) return null
        val media = soma * (1.0 / n)
        soma = Vetor3.ZERO
        n = 0
        proximoNs += periodoNs
        if (proximoNs <= instanteNs) proximoNs = instanteNs + periodoNs
        return media
    }

    fun reiniciar() {
        soma = Vetor3.ZERO
        n = 0
        iniciado = false
    }

    companion object {
        /** 25 Hz. */
        const val PERIODO_NS = 40_000_000L
        private const val FOLGA_NS = 500_000L
    }
}
