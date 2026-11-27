package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v2.6.0: estatísticas da viagem e consumo MAF/MAP com a calibração do carro. */
class PercursoCalibracaoTest {

    private val comMaf = DadosObd(mapOf(Pids.MAF to 20.0))
    private val semMaf = DadosObd(mapOf(Pids.RPM to 2400.0, Pids.PRESSAO_COLETOR to 60.0, Pids.TEMPERATURA_AR_ADMISSAO to 27.0))

    private fun litros(dados: DadosObd, motor: ParametrosMotor) = TelemetriaCalculada.consumoInstantaneo(dados, motor)?.litrosPorHora

    @Test
    fun `viagem separa parado de movimento, ignora o 255 e fecha consumo e custo`() {
        val acumulador = AcumuladorPercurso()
        // 60 s em marcha lenta, 60 s a 72 km/h, com um 255 no meio.
        for (s in 0..60) acumulador.registrar(0.0, 800.0, 1.0, s * 1000L)
        for (s in 61..120) acumulador.registrar(if (s == 90) 255.0 else 72.0, 2000.0, 7.0, s * 1000L)
        val e = acumulador.estatisticas

        assertEquals(60.0, e.segundosParado, 1e-6)
        assertEquals(60.0, e.segundosMovimento, 1e-6)
        assertEquals(72.0, e.velocidadeMaximaKmh!!, 0.0)
        // 1 s de transição pela média (36 km/h) + 59 s a 72 km/h.
        assertEquals(1.19, e.distanciaKm, 1e-6)
        assertEquals(35.7, e.velocidadeMediaKmh!!, 1e-6)
        assertEquals(71.4, e.velocidadeMediaMovimentoKmh!!, 1e-6)
        assertEquals(1400.0, e.rpmMedio!!, 1e-6)
        assertEquals(2000.0, e.rpmMaximo!!, 0.0)
        assertEquals(8.0 / 60.0, e.litros, 1e-6)
        assertEquals(e.distanciaKm / e.litros, e.kmPorLitro!!, 1e-9)
        assertEquals(100.0, e.kmPorLitro!! * e.litrosPor100Km!!, 1e-9)
        assertEquals(e.litros * 6.0, e.custo(6.0)!!, 1e-9)
        assertNull(e.custo(null))
    }

    @Test
    fun `calibracao padrao nao muda o consumo e o combustivel escolhido vale sobre o flex`() {
        val padrao = CalibracaoConsumo().parametros("Onix 1.0", TipoCombustivel.FLEX, 1.1)
        assertEquals(ParametrosMotor.deVeiculo("Onix 1.0", TipoCombustivel.FLEX, 1.1), padrao)
        // Flex automático segue o 0152; com "Gasolina" escolhida, não.
        val etanol = DadosObd(comMaf.valores + (Pids.PERCENTUAL_ETANOL to 100.0))
        val gasolina = CalibracaoConsumo(combustivel = CombustivelCalibracao.GASOLINA).parametros("Onix 1.0", TipoCombustivel.FLEX, 1.1)
        val emEtanol = litros(etanol, padrao)!!
        assertEquals(20.0 / 9.0 * 3600 / 809.0 * 1.1, emEtanol, 1e-6)
        assertEquals(litros(comMaf, padrao)!!, litros(etanol, gasolina)!!, 1e-9)
    }

    @Test
    fun `sem MAF o flex usa MAP, IAT e RPM com a eficiencia e o ajuste do carro`() {
        val base = CalibracaoConsumo(cilindradaLitros = 1.0, eficienciaVolumetrica = 0.8)
        val motor = base.parametros(null, TipoCombustivel.FLEX)
        val ar = 2400.0 / 120.0 * 1.0 * 0.8 * 60.0 / (0.287 * 300.15)
        val esperado = TelemetriaCalculada.litrosPorHoraDaMassaAr(ar, semMaf, motor)
        assertEquals(esperado, litros(semMaf, motor)!!, 1e-9)
        assertEquals(MetodoConsumo.SPEED_DENSITY, TelemetriaCalculada.consumoInstantaneo(semMaf, motor)!!.metodo)
        assertEquals(esperado * 1.1, litros(semMaf, base.copy(ajustePercentual = 10.0).parametros(null, TipoCombustivel.FLEX))!!, 1e-9)
    }

    @Test
    fun `diesel S10 usa MAF, depois a ECU e so estima pelo MAP com a eficiencia informada`() {
        val s10 = CalibracaoConsumo(combustivel = CombustivelCalibracao.DIESEL_S10)
        val motor = s10.parametros("Hilux 3.0", TipoCombustivel.DIESEL)
        assertEquals(20.0 / 27.0 * 3600 / 835.0, litros(comMaf, motor)!!, 1e-9)
        assertEquals(9.5, litros(DadosObd(semMaf.valores + (Pids.CONSUMO_ECU to 9.5)), motor)!!, 0.0)
        assertNull(litros(semMaf, motor))

        val comEficiencia = s10.copy(eficienciaVolumetrica = 0.9).parametros("Hilux 3.0", TipoCombustivel.DIESEL)
        val ar = 2400.0 / 120.0 * 3.0 * 0.9 * 60.0 / (0.287 * 300.15)
        assertEquals(ar / 27.0 * 3600 / 835.0, litros(semMaf, comEficiencia)!!, 1e-9)
        // A ECU continua tendo preferência sobre a estimativa pelo MAP.
        assertEquals(9.5, litros(DadosObd(semMaf.valores + (Pids.CONSUMO_ECU to 9.5)), comEficiencia)!!, 0.0)
    }

    @Test
    fun `ajuste pela bomba compoe com o atual e recusa valores incoerentes`() {
        assertEquals(10.0, CalibracaoConsumo().ajustePelaBomba(44.0, 40.0)!!, 1e-9)
        assertEquals(21.0, CalibracaoConsumo(ajustePercentual = 10.0).ajustePelaBomba(44.0, 40.0)!!, 1e-9)
        assertNull(CalibracaoConsumo().ajustePelaBomba(40.0, 2.0))
        assertNull(CalibracaoConsumo().ajustePelaBomba(40.0, 10.0))
    }
}
