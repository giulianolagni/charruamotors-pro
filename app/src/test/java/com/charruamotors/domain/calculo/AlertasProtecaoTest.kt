package com.charruamotors.domain.calculo

import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DadosObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7: limites editáveis (água 98 °C, ATF 105 °C, alternador 13,2 V a partir de 400 rpm (v2.2.6)) e o disparador do som. */
class AlertasProtecaoTest {

    private val limites = LimitesProtecao()

    private fun dados(vararg v: Pair<String, Double>) = DadosObd(mapOf(*v))

    private fun tipos(d: DadosObd?, l: LimitesProtecao = limites) = AlertasProtecao.avaliar(d, l).map { it.tipo }

    // ---------------------------------------------------------------- limites padrão

    @Test
    fun `padroes do veiculo sao 98 graus agua 105 graus ATF e 13,2 volts`() {
        val v = Veiculo(marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 1, tipoCombustivel = TipoCombustivel.DIESEL)
        assertEquals(98, v.tempMaxAguaC)
        assertEquals(105, v.tempMaxAtfC)
        assertEquals(13.2, v.tensaoMinAlternadorV, 0.0)
        assertEquals(LimitesProtecao(98.0, 105.0, 13.2), LimitesProtecao.deVeiculo(v))
        assertEquals(LimitesProtecao(), LimitesProtecao.deVeiculo(null))
        assertEquals(400.0, limites.rpmMinimoTensao, 0.0)
    }

    @Test
    fun `os limites do cadastro do veiculo valem no lugar dos padroes`() {
        val v = Veiculo(
            marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 1, tipoCombustivel = TipoCombustivel.DIESEL,
            tempMaxAguaC = 104, tempMaxAtfC = 110, tensaoMinAlternadorV = 13.5,
        )
        assertEquals(LimitesProtecao(104.0, 110.0, 13.5), LimitesProtecao.deVeiculo(v))
    }

    // ---------------------------------------------------------------- água e ATF

    @Test
    fun `agua dispara a partir do limite e nao antes`() {
        assertTrue(tipos(dados("0105" to 97.9)).isEmpty())
        assertEquals(listOf(TipoAlertaProtecao.AGUA), tipos(dados("0105" to 98.0)))
        val alerta = AlertasProtecao.avaliar(dados("0105" to 104.0), limites).single()
        assertEquals(104.0, alerta.valor, 0.0)
        assertEquals(98.0, alerta.limite, 0.0)
    }

    @Test
    fun `ATF dispara a partir do limite pelo cambio ou pelo espelho do motor`() {
        assertTrue(tipos(dados("TOYOTA_ATF_CAMBIO" to 104.0)).isEmpty())
        assertEquals(listOf(TipoAlertaProtecao.ATF), tipos(dados("TOYOTA_ATF_CAMBIO" to 105.0)))
        assertEquals(listOf(TipoAlertaProtecao.ATF), tipos(dados("TOYOTA_ATF_ECM" to 112.0))) // câmbio sem resposta
    }

    // ---------------------------------------------------------------- alternador

    @Test
    fun `tensao baixa so alerta com o motor a partir de 400 rpm`() {
        assertEquals(listOf(TipoAlertaProtecao.TENSAO), tipos(dados("ATRV" to 12.9, "010C" to 850.0)))
        // v2.2.6: RPM ≥ 400 (inclusivo); abaixo disso é o motor de partida girando.
        assertEquals(listOf(TipoAlertaProtecao.TENSAO), tipos(dados("ATRV" to 12.9, "010C" to 400.0)))
        assertTrue(tipos(dados("ATRV" to 12.9, "010C" to 399.0)).isEmpty())
        assertTrue(tipos(dados("ATRV" to 12.9, "010C" to 250.0)).isEmpty()) // partida
        assertTrue(tipos(dados("ATRV" to 12.9, "010C" to 0.0)).isEmpty()) // motor desligado: bateria em repouso
        assertTrue(tipos(dados("ATRV" to 12.9)).isEmpty()) // sem RPM
        assertTrue(tipos(dados("ATRV" to 13.2, "010C" to 1500.0)).isEmpty()) // no limite: ok
        assertTrue(tipos(dados("ATRV" to 14.2, "010C" to 1500.0)).isEmpty())
    }

    @Test
    fun `leituras ausentes ou invalidas nunca disparam e todos podem disparar juntos`() {
        assertTrue(AlertasProtecao.avaliar(null, limites).isEmpty())
        assertTrue(tipos(dados()).isEmpty())
        assertTrue(tipos(dados("0105" to Double.NaN, "ATRV" to Double.NaN, "010C" to 900.0)).isEmpty())
        assertEquals(
            listOf(TipoAlertaProtecao.AGUA, TipoAlertaProtecao.ATF, TipoAlertaProtecao.TENSAO),
            tipos(dados("0105" to 110.0, "TOYOTA_ATF_CAMBIO" to 120.0, "ATRV" to 12.0, "010C" to 2000.0)),
        )
    }

    @Test
    fun `limites mais altos silenciam o alerta`() {
        val d = dados("0105" to 100.0)
        assertEquals(listOf(TipoAlertaProtecao.AGUA), tipos(d))
        assertTrue(tipos(d, limites.copy(aguaMaxC = 105.0)).isEmpty())
    }

    // ---------------------------------------------------------------- mostradores em vermelho

    @Test
    fun `cada mostrador fica vermelho so pelo proprio alerta`() {
        val alertas = AlertasProtecao.avaliar(dados("0105" to 110.0, "TOYOTA_ATF_CAMBIO" to 80.0, "ATRV" to 14.0, "010C" to 900.0), limites)
        assertTrue(SensorPainel.TEMP_MOTOR.emAlerta(alertas))
        assertFalse(SensorPainel.TEMP_CAMBIO.emAlerta(alertas))
        assertFalse(SensorPainel.TENSAO.emAlerta(alertas))
        assertFalse(SensorPainel.RPM.emAlerta(alertas))
        assertNull(SensorPainel.RPM.alertaDeProtecao)
        assertEquals(TipoAlertaProtecao.TENSAO, SensorPainel.TENSAO.alertaDeProtecao)
    }
}
