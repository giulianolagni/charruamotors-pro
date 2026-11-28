package com.charruamotors.direcao.forcag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Calibração do suporte, projeção nos eixos do carro, filtros e picos da Força G. */
class ForcaGTest {

    private val g = GRAVIDADE_MS2

    private fun assertVetor(esperado: Vetor3, obtido: Vetor3) {
        assertEquals("x de $obtido", esperado.x, obtido.x, 1e-9)
        assertEquals("y de $obtido", esperado.y, obtido.y, 1e-9)
        assertEquals("z de $obtido", esperado.z, obtido.z, 1e-9)
    }

    // ---------------------------------------------------------------- calibração e projeção

    @Test
    fun `celular em pe de frente para o motorista - frente e o verso do aparelho`() {
        val c = CalibracaoSuporte.de(Vetor3(0.0, g, 0.0))!!
        assertVetor(Vetor3(0.0, 0.0, -1.0), c.frente)
        assertVetor(Vetor3(1.0, 0.0, 0.0), c.direita)

        // Acelerando (para o verso) e fazendo curva à direita.
        val f = c.projetar(Vetor3(0.5 * g, 0.0, -0.3 * g))
        assertEquals(0.3, f.longitudinal, 1e-9)
        assertEquals(0.5, f.lateral, 1e-9)
    }

    @Test
    fun `suporte inclinado e celular deitado apontam a mesma frente`() {
        val inclinacao = Math.toRadians(30.0)
        val inclinado = CalibracaoSuporte.de(Vetor3(0.0, g * cos(inclinacao), g * sin(inclinacao)))!!
        assertVetor(Vetor3(0.0, sin(inclinacao), -cos(inclinacao)), inclinado.frente)
        assertVetor(Vetor3(1.0, 0.0, 0.0), inclinado.direita)
        // Frear: aceleração para trás do carro. A gravidade não vaza para os eixos horizontais.
        val freio = inclinado.projetar(inclinado.frente * (-0.8 * g))
        assertEquals(-0.8, freio.longitudinal, 1e-9)
        assertEquals(0.0, freio.lateral, 1e-9)
        assertEquals(0.0, inclinado.projetar(inclinado.cima * g).total, 1e-9)

        val deitado = CalibracaoSuporte.de(Vetor3(0.0, 0.0, g))!!
        assertVetor(Vetor3(0.0, 1.0, 0.0), deitado.frente)
        assertVetor(Vetor3(1.0, 0.0, 0.0), deitado.direita)
    }

    @Test
    fun `paisagem usa o lado que esta para cima e, deitado, a rotacao da tela`() {
        val emPe = CalibracaoSuporte.de(Vetor3(g, 0.0, 0.0), RotacaoTela.R90)!!
        assertVetor(Vetor3(0.0, 0.0, -1.0), emPe.frente)
        assertVetor(Vetor3(0.0, -1.0, 0.0), emPe.direita)

        val deitado = CalibracaoSuporte.de(Vetor3(0.0, 0.0, g), RotacaoTela.R270)!!
        assertVetor(Vetor3(-1.0, 0.0, 0.0), deitado.frente)
        assertVetor(Vetor3(0.0, 1.0, 0.0), deitado.direita)
    }

    @Test
    fun `calibracao desconta o vies, sobrevive a gravacao e recusa lixo`() {
        val c = CalibracaoSuporte.de(Vetor3(0.1, g, 0.4), vies = Vetor3(0.2, 0.0, -0.1))!!
        assertEquals(0.0, c.projetar(Vetor3(0.2, 0.0, -0.1)).total, 1e-9)
        assertEquals(c, CalibracaoSuporte.decodificar(c.codificar()))

        assertNull(CalibracaoSuporte.decodificar(null))
        assertNull(CalibracaoSuporte.decodificar("1;2;x"))
        assertNull(CalibracaoSuporte.decodificar(List(12) { "0.0" }.joinToString(";")))
        assertNull(CalibracaoSuporte.de(Vetor3.ZERO))
    }

    // ---------------------------------------------------------------- filtros

    @Test
    fun `filtro de gravidade zera o repouso e deixa passar um tranco`() {
        val filtro = FiltroGravidade()
        var t = 0L
        fun passo(v: Vetor3): Vetor3 = filtro.remover(v, t).also { t += 40_000_000 }
        repeat(100) { assertEquals(0.0, passo(Vetor3(0.0, g, 0.0)).norma, 1e-9) }

        var tranco = Vetor3.ZERO
        repeat(5) { tranco = passo(Vetor3(0.0, g, -3.0)) } // 200 ms de aceleração
        assertEquals(-3.0, tranco.z, 0.3)
        // Mantida por muito tempo, a aceleração é absorvida como se fosse gravidade.
        repeat(500) { tranco = passo(Vetor3(0.0, g, -3.0)) }
        assertEquals(0.0, tranco.z, 0.05)
    }

    @Test
    fun `amostrador entrega 25 Hz com a media das leituras de um sensor a 100 Hz`() {
        val amostrador = AmostradorEstavel()
        val saidas = (0 until 400).mapNotNull { i -> amostrador.adicionar(Vetor3((i % 4).toDouble(), 0.0, 0.0), i * 10_000_000L) }
        assertEquals(100.0, saidas.size.toDouble(), 2.0)
        assertEquals(1.5, saidas.last().x, 1e-9)
    }

    // ---------------------------------------------------------------- medidor

    private class Bancada {
        var agoraNs = 0L
        val medidor = MedidorForcaG { agoraNs / 1_000_000 }

        /** [segundos] de leituras a 25 Hz. */
        fun alimentar(segundos: Double, acelerometro: Vetor3, linear: Vetor3?) {
            repeat((segundos * 25).toInt()) {
                agoraNs += 40_000_000
                medidor.acelerometro(acelerometro, agoraNs)
                linear?.let { medidor.aceleracaoLinear(it, agoraNs) }
            }
        }
    }

    @Test
    fun `sem calibracao nao ha leitura e com o carro balancando ela e recusada`() {
        val b = Bancada()
        assertEquals(ResultadoCalibracao.SEM_LEITURAS, b.medidor.calibrar(RotacaoTela.R0).first)
        b.alimentar(1.0, Vetor3(0.0, g, 0.0), Vetor3.ZERO)
        assertNull(b.medidor.leituraAtual())

        repeat(50) { i -> b.alimentar(0.04, Vetor3(if (i % 2 == 0) 3.0 else -3.0, g, 0.0), Vetor3.ZERO) }
        assertEquals(ResultadoCalibracao.EM_MOVIMENTO, b.medidor.calibrar(RotacaoTela.R0).first)
    }

    @Test
    fun `calibrado, o medidor guarda os picos de cada direcao e a leitura expira`() {
        val b = Bancada()
        val repouso = Vetor3(0.0, g, 0.0)
        b.alimentar(1.5, repouso, Vetor3.ZERO)
        val (resultado, calibracao) = b.medidor.calibrar(RotacaoTela.R0)
        assertEquals(ResultadoCalibracao.OK, resultado)
        assertNotNull(calibracao)

        b.alimentar(2.0, repouso, Vetor3(0.0, 0.0, -0.4 * g)) // acelera 0,4 G
        b.alimentar(2.0, repouso, Vetor3(0.0, 0.0, 0.9 * g)) // freia 0,9 G
        b.alimentar(2.0, repouso, Vetor3(-0.6 * g, 0.0, 0.0)) // curva à esquerda
        b.alimentar(2.0, repouso, Vetor3(0.2 * g, 0.0, 0.0)) // curva à direita

        val estado = b.medidor.estado.value
        assertEquals(0.4, estado.picos.aceleracao, 0.01)
        assertEquals(0.9, estado.picos.frenagem, 0.01)
        assertEquals(0.6, estado.picos.curvaEsquerda, 0.01)
        assertEquals(0.2, estado.picos.curvaDireita, 0.01)
        assertEquals(MedidorForcaG.TAMANHO_RASTRO, estado.rastro.size)
        assertEquals(0.2, b.medidor.leituraAtual()!!.lateral, 0.01)

        b.medidor.zerarPicos()
        assertEquals(PicosForcaG(), b.medidor.estado.value.picos)
        b.agoraNs += 2_000_000_000
        assertNull(b.medidor.leituraAtual())
    }

    @Test
    fun `sem o sensor linear o acelerometro passa pelo filtro de gravidade`() {
        val b = Bancada()
        b.medidor.reiniciarSinal(comAceleracaoLinear = false)
        b.alimentar(1.5, Vetor3(0.0, g, 0.0), null)
        assertEquals(ResultadoCalibracao.OK, b.medidor.calibrar(RotacaoTela.R0).first)

        b.alimentar(0.4, Vetor3(0.0, g, -0.5 * g), null)

        val f = b.medidor.leituraAtual()!!
        assertTrue("longitudinal ${f.longitudinal}", f.longitudinal in 0.35..0.5)
        assertEquals(0.0, f.lateral, 1e-9)
    }
}
