package com.charruamotors.domain.calculo

import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.UnidadePressao
import com.charruamotors.ui.componentes.CurvaPressao
import com.charruamotors.ui.componentes.PontoTelemetria
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressaoTurboTest {

    private fun dados(map: Double, rpm: Double, vararg extras: Pair<String, Double>) =
        DadosObd(mapOf(Pids.PRESSAO_COLETOR to map, Pids.RPM to rpm, *extras))

    private fun CalculadoraBoost.boost(d: DadosObd, diesel: Boolean = false) = aplicar(d, diesel)[Pids.PRESSAO_BOOST]!!

    @Test
    fun `barometro vem da ECU, do MAP com motor parado ou do padrao`() {
        val calc = CalculadoraBoost()
        // Conectou com o motor já girando e sem barômetro: 101,3.
        assertEquals(98.7, calc.boost(dados(200.0, 2000.0)), 0.001)
        assertEquals(FonteBarometro.PADRAO, calc.barometro.fonte)

        calc.reiniciar()
        calc.boost(dados(93.0, 0.0))
        calc.boost(dados(95.0, 0.0))
        // Depois da partida a média inicial (94) fica; o motor desligado de novo não a altera.
        assertEquals(106.0, calc.boost(dados(200.0, 2000.0)), 0.001)
        calc.boost(dados(60.0, 0.0))
        assertEquals(Barometro(94.0, FonteBarometro.MAP_PARADO), calc.barometro)

        // O 0133 tem prioridade e segue valendo no instantâneo sem ele.
        assertEquals(105.0, calc.boost(dados(200.0, 2000.0, "0133" to 95.0)), 0.001)
        assertEquals(105.0, calc.boost(dados(200.0, 2000.0)), 0.001)
        assertEquals(FonteBarometro.ECU, calc.barometro.fonte)
    }

    @Test
    fun `diesel zera so o micro-negativo, flex mantem o vacuo, e converte para bar e psi`() {
        val calc = CalculadoraBoost()
        val baro = "0133" to 100.0
        assertEquals(0.0, calc.boost(dados(96.0, 800.0, baro), diesel = true), 0.001)
        assertEquals(-8.0, calc.boost(dados(92.0, 800.0, baro), diesel = true), 0.001)
        assertEquals(-4.0, calc.boost(dados(96.0, 800.0, baro)), 0.001)
        assertEquals(-70.0, calc.boost(dados(30.0, 800.0, baro)), 0.001)

        val bar = ConversorUnidades(pressaoMetrica = UnidadePressao.BAR)
        val psi = ConversorUnidades(pressaoMetrica = UnidadePressao.PSI)
        assertEquals(1.2, SensorPainel.BOOST.exibir(120.0, bar).valor, 0.001)
        assertEquals(17.405, SensorPainel.BOOST.exibir(120.0, psi).valor, 0.001)
        assertEquals(0f, SensorPainel.BOOST.escala(bar, diesel = true).minimo)
        assertEquals(-1f, SensorPainel.BOOST.escala(bar).minimo)
        assertEquals(2f, SensorPainel.BOOST.escala(bar).maximo)

        // Rodapé: eixo em bar, com piso negativo só quando há vácuo.
        val curva = CurvaPressao(bar, boost = true)
        val (min, max) = curva.eixo(listOf(PontoTelemetria(0, 800.0, 30.0, -70.0), PontoTelemetria(1, 3000.0, 220.0, 120.0)))
        assertTrue(min <= -0.7 && max >= 1.2)
        assertEquals(0.0 to 1.0, curva.eixo(listOf(PontoTelemetria(0, 800.0, 100.0, 0.0))))
    }
}
