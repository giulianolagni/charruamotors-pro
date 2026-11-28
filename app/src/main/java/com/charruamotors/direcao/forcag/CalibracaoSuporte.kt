package com.charruamotors.direcao.forcag

import kotlin.math.abs
import kotlin.math.sqrt

/** Aceleração padrão da gravidade (m/s²): converte as leituras em G. */
const val GRAVIDADE_MS2 = 9.80665

/** Vetor nos eixos do aparelho (`SensorEvent`): x = direita da tela, y = topo, z = para fora da tela. */
data class Vetor3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vetor3) = Vetor3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vetor3) = Vetor3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vetor3(x * k, y * k, z * k)

    fun escalar(o: Vetor3): Double = x * o.x + y * o.y + z * o.z

    fun vetorial(o: Vetor3) = Vetor3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val norma: Double get() = sqrt(escalar(this))

    val finito: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    /** Vetor unitário; null se for curto demais para ter direção. */
    fun normalizado(minimo: Double = 1e-6): Vetor3? = norma.takeIf { it > minimo }?.let { this * (1.0 / it) }

    companion object {
        val ZERO = Vetor3(0.0, 0.0, 0.0)
    }
}

/**
 * Força G do carro, na aceleração do veículo: [longitudinal] positiva = acelerando, negativa =
 * freando; [lateral] positiva = curva à direita, negativa = à esquerda.
 */
data class ForcaG(val longitudinal: Double, val lateral: Double) {
    val total: Double get() = sqrt(longitudinal * longitudinal + lateral * lateral)
}

/** Para onde aponta o topo da tela em uso (`Display.rotation`); só decide com o celular quase deitado. */
enum class RotacaoTela(val topo: Vetor3) {
    R0(Vetor3(0.0, 1.0, 0.0)),
    R90(Vetor3(1.0, 0.0, 0.0)),
    R180(Vetor3(0.0, -1.0, 0.0)),
    R270(Vetor3(-1.0, 0.0, 0.0)),
    ;

    companion object {
        /** `Surface.ROTATION_0..270` (0..3). */
        fun deCodigo(codigo: Int): RotacaoTela = entries.getOrElse(codigo) { R0 }
    }
}

/**
 * Orientação do celular no suporte do carro, capturada com o carro parado: os eixos do veículo
 * ([cima], [frente], [direita]) escritos nos eixos do aparelho e o [vies] do sensor em repouso.
 *
 * Premissa: a tela está voltada para a traseira do carro (para o motorista) ou, com o celular
 * deitado, o topo aponta para a frente. Um suporte girado na direção do motorista mistura um pouco
 * os dois eixos: só a gravidade não revela o rumo do carro.
 */
data class CalibracaoSuporte(val cima: Vetor3, val frente: Vetor3, val direita: Vetor3, val vies: Vetor3 = Vetor3.ZERO) {

    /** Projeta a aceleração linear (m/s², sem gravidade) nos eixos do carro, em G. */
    fun projetar(linear: Vetor3): ForcaG {
        val a = linear - vies
        return ForcaG(a.escalar(frente) / GRAVIDADE_MS2, a.escalar(direita) / GRAVIDADE_MS2)
    }

    fun codificar(): String = listOf(cima, frente, direita, vies).flatMap { listOf(it.x, it.y, it.z) }.joinToString(";")

    companion object {
        /** Abaixo disto a gravidade quase não aparece no plano da tela: o celular está deitado. */
        private const val LIMIAR_DEITADO = 0.35

        /**
         * Calibração a partir da [gravidade] medida em repouso (o acelerômetro lê +1 G no eixo que
         * aponta para cima). A frente é a horizontal de `topo da tela − Z`: o topo vale com o celular
         * deitado, o `−Z` (verso do aparelho) com ele em pé, e a soma cobre as inclinações entre os
         * dois. Null se a leitura não definir uma direção.
         */
        fun de(gravidade: Vetor3, rotacao: RotacaoTela = RotacaoTela.R0, vies: Vetor3 = Vetor3.ZERO): CalibracaoSuporte? {
            if (!gravidade.finito || !vies.finito) return null
            val cima = gravidade.normalizado(1.0) ?: return null
            // O lado da tela que está mais para cima (retrato ou paisagem); deitado, o da rotação em uso.
            val topo = if (maxOf(abs(cima.x), abs(cima.y)) < LIMIAR_DEITADO) {
                rotacao.topo
            } else if (abs(cima.y) >= abs(cima.x)) {
                Vetor3(0.0, if (cima.y >= 0) 1.0 else -1.0, 0.0)
            } else {
                Vetor3(if (cima.x >= 0) 1.0 else -1.0, 0.0, 0.0)
            }
            val referencia = topo + Vetor3(0.0, 0.0, -1.0)
            val frente = (referencia - cima * referencia.escalar(cima)).normalizado(0.1) ?: return null
            return CalibracaoSuporte(cima, frente, frente.vetorial(cima), vies)
        }

        /** Null para texto ausente ou corrompido. */
        fun decodificar(texto: String?): CalibracaoSuporte? {
            val n = texto?.split(';')?.map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null } ?: return null
            if (n.size != 12) return null
            fun v(i: Int) = Vetor3(n[i], n[i + 1], n[i + 2])
            val c = CalibracaoSuporte(v(0), v(3), v(6), v(9))
            return c.takeIf { listOf(it.cima, it.frente, it.direita).all { eixo -> abs(eixo.norma - 1.0) < 1e-3 } }
        }
    }
}
