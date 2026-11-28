package com.charruamotors.oficina.replay

import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
import com.charruamotors.sistema.unidades.UnidadePressao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.11.2: unidades de exibição no replay e piso do eixo Y. */
class UnidadesReplayTest {

    private val log = LeitorCsvLog.ler(
        "timestamp_ms,MAP (kPa) [010B],Rail (kPa) [0123],RPM (rpm) [010C]\n0,100,35000,0\n1000,250,,3800\n",
    )

    @Test
    fun `MAP e rail saem em bar ou psi e o log original continua em kPa`() {
        val bar = log.emUnidades(ConversorUnidades(pressaoMetrica = UnidadePressao.BAR))
        assertEquals(listOf("bar", "bar", "rpm"), bar.colunas.map { it.unidade })
        assertEquals("MAP (bar)", bar.colunas[0].rotulo)
        val map = AnaliseReplay.estatisticas(bar.series[0], 0..1)!!
        assertEquals(1.0, map.minimo, 1e-9)
        assertEquals(2.5, map.maximo, 1e-9)
        assertEquals(1.75, map.media, 1e-9)
        assertEquals(350.0, bar.series[1][0], 1e-9)
        assertTrue(bar.series[1][1].isNaN())

        val psi = log.emUnidades(ConversorUnidades(sistema = SistemaUnidades.IMPERIAL))
        assertEquals("psi", psi.colunas[0].unidade)
        assertEquals(14.5038, psi.series[0][0], 1e-3)
        assertEquals(5076.32, psi.series[1][0], 1e-2)

        // Com kPa escolhido o MAP fica em kPa e o rail segue o Painel (bar).
        assertEquals(listOf("kPa", "bar", "rpm"), log.emUnidades(ConversorUnidades.PADRAO).colunas.map { it.unidade })
        assertEquals(35000.0, log.series[1][0], 0.0)
    }

    @Test
    fun `serie sem valores negativos nao ganha piso negativo no eixo Y`() {
        val rpm = AnaliseReplay.escala(AnaliseReplay.estatisticas(log.series[2], 0..1)!!)
        assertEquals(0.0, rpm.first, 0.0)
        assertTrue(rpm.second > 3800.0)
        assertEquals(0.0, AnaliseReplay.escala(EstatisticaSerie(0.0, 0.0, 0.0, 3)).first, 0.0)
        assertEquals(0.0, AnaliseReplay.incluirNaEscala(1.0 to 15.0, listOf(0.5)).first, 0.0)
        // Com leitura negativa (fuel trim) a margem continua abaixo do mínimo.
        assertTrue(AnaliseReplay.escala(EstatisticaSerie(-5.0, 5.0, 0.0, 3)).first < -5.0)
    }
}
