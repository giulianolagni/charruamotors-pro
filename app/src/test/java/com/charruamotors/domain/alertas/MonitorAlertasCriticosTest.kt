package com.charruamotors.domain.alertas

import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.4.0: debounce, outliers, histerese, motor ligado, silêncio, DTC/MIL e isolamento por perfil. */
class MonitorAlertasCriticosTest {

    private class Memoria : ArmazenamentoChaveValor {
        private val guardado = mutableMapOf<String, Any?>()
        override fun lerTexto(chave: String): String? = guardado[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean): Boolean = guardado[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            valores.forEach { (k, v) -> if (v == null) guardado.remove(k) else guardado[k] = v }
        }
    }

    private val monitor = MonitorAlertasCriticos()

    private fun agua(c: Double, ms: Long) = monitor.processar(mapOf("0105" to c), setOf("0105"), ms)

    private fun tensao(v: Double, ms: Long, rpm: Double = 800.0) =
        monitor.processar(mapOf("010C" to rpm, "ATRV" to v), setOf("010C", "ATRV"), ms)

    /** Três leituras seguidas: o suficiente para confirmar. */
    private fun confirmarAgua(c: Double, inicioMs: Long): AvaliacaoAlertas {
        agua(c, inicioMs)
        agua(c, inicioMs + 500)
        return agua(c, inicioMs + 1_000)
    }

    // ---------------------------------------------------------------- debounce

    @Test
    fun `dispara com duas leituras cobrindo 3 segundos`() {
        assertTrue(agua(109.0, 0).estado.ativos.isEmpty())
        assertTrue(agua(109.0, 2_999).estado.ativos.isEmpty())

        monitor.reiniciar()
        agua(109.0, 10_000)
        val r = agua(109.0, 13_000)
        assertEquals(NivelAlerta.CRITICO, r.estado.principal?.nivel)
        assertEquals(NivelAlerta.CRITICO, r.som)
        assertEquals("CRÍTICO · ÁGUA QUENTE 109 °C", r.entradas.single().texto)
    }

    @Test
    fun `dispara com tres leituras seguidas mesmo antes de 3 segundos`() {
        assertEquals(NivelAlerta.CRITICO, confirmarAgua(109.0, 0).som)
    }

    @Test
    fun `valor retido da fila lenta nao conta como leitura nova`() {
        agua(120.0, 0)
        // Os ciclos seguintes só releem o RPM: a água continua no instantâneo, mas não é fresca.
        repeat(20) { i ->
            val r = monitor.processar(mapOf("0105" to 120.0, "010C" to 900.0), setOf("010C"), 1_000L * (i + 1))
            assertTrue(r.estado.ativos.isEmpty())
        }
    }

    // ---------------------------------------------------------------- outliers

    @Test
    fun `um pico isolado nao dispara`() {
        agua(90.0, 0)
        agua(130.0, 5_000)
        agua(90.0, 10_000)
        assertTrue(agua(130.0, 15_000).estado.ativos.isEmpty())
    }

    @Test
    fun `valores impossiveis sao ignorados`() {
        repeat(5) { assertTrue(agua(215.0, it * 2_000L).estado.ativos.isEmpty()) }
        repeat(5) { assertTrue(tensao(0.0, it * 2_000L).estado.ativos.isEmpty()) }
        repeat(5) { assertTrue(agua(Double.NaN, it * 2_000L).estado.ativos.isEmpty()) }
    }

    @Test
    fun `leituras separadas por mais de dois minutos nao formam sequencia`() {
        agua(109.0, 0)
        assertTrue(agua(109.0, 200_000).estado.ativos.isEmpty())
    }

    @Test
    fun `confirmacao pede a leitura rapida da agua ate decidir`() {
        agua(109.0, 0)
        assertEquals(setOf("0105"), monitor.chavesEmConfirmacao)
        agua(109.0, 3_000)
        assertTrue(monitor.chavesEmConfirmacao.isEmpty())
    }

    // ---------------------------------------------------------------- níveis e histerese

    @Test
    fun `atencao sobe para critico com novo bipe`() {
        assertEquals(NivelAlerta.ATENCAO, confirmarAgua(104.0, 0).som)
        val r = confirmarAgua(108.0, 5_000)
        assertEquals(NivelAlerta.CRITICO, r.som)
        assertEquals(1, r.entradas.size)
    }

    @Test
    fun `histerese so rearma abaixo de 103 graus`() {
        confirmarAgua(109.0, 0)
        // 105 °C ainda não é seguro: continua crítico e não gera nova entrada.
        val meio = confirmarAgua(105.0, 5_000)
        assertEquals(NivelAlerta.CRITICO, meio.estado.principal?.nivel)
        assertTrue(confirmarAgua(109.0, 10_000).entradas.isEmpty())
        // Uma única leitura baixa (corrompida) não rearma.
        assertNotNull(agua(40.0, 15_000).estado.principal)
        agua(109.0, 16_000)
        // Duas seguidas na faixa segura encerram; o próximo aquecimento volta a disparar.
        agua(102.0, 20_000)
        assertTrue(agua(102.0, 21_000).estado.ativos.isEmpty())
        assertEquals(1, confirmarAgua(109.0, 25_000).entradas.size)
    }

    @Test
    fun `critico repete a cada 10 segundos`() {
        confirmarAgua(109.0, 0)
        assertNull(monitor.avaliar(10_999).som)
        assertEquals(NivelAlerta.CRITICO, monitor.avaliar(11_000).som)
    }

    // ---------------------------------------------------------------- ATF e tensão

    @Test
    fun `atf usa os proprios limites em qualquer das chaves`() {
        fun atf(c: Double, ms: Long) = monitor.processar(mapOf("GM_ATF" to c), null, ms)
        atf(121.0, 0)
        val r = atf(121.0, 3_000)
        assertEquals(RegraAlerta.TEMP_ATF, r.estado.principal?.regra)
        assertEquals(NivelAlerta.CRITICO, r.estado.principal?.nivel)
    }

    @Test
    fun `tensao so vale com rpm acima de 400`() {
        repeat(5) { assertTrue(tensao(10.8, it * 2_000L, rpm = 400.0).estado.ativos.isEmpty()) }
        repeat(5) { assertTrue(monitor.processar(mapOf("ATRV" to 10.8), null, 20_000 + it * 2_000L).estado.ativos.isEmpty()) }
        tensao(11.2, 40_000)
        val r = tensao(11.2, 43_000)
        assertEquals("CRÍTICO · TENSÃO / ALTERNADOR 11,2 V", r.estado.principal?.texto)
        // Motor desligado encerra na hora.
        assertTrue(tensao(11.2, 44_000, rpm = 0.0).estado.ativos.isEmpty())
    }

    @Test
    fun `sobretensao dispara e a faixa segura tem folga`() {
        tensao(15.8, 0)
        assertNotNull(tensao(15.8, 3_000).estado.principal)
        tensao(15.4, 4_000)
        assertNotNull(tensao(15.4, 5_000).estado.principal)
        tensao(14.2, 6_000)
        assertTrue(tensao(14.2, 7_000).estado.ativos.isEmpty())
    }

    // ---------------------------------------------------------------- silêncio

    @Test
    fun `silenciar cala o bipe por 5 minutos e mantem o visual`() {
        confirmarAgua(109.0, 0)
        val calado = monitor.silenciar(2_000)
        assertNotNull(calado.principal)
        assertTrue(!calado.podeSilenciar)
        assertNull(monitor.avaliar(60_000).som)
        assertNull(monitor.avaliar(301_999).som)
        assertEquals(NivelAlerta.CRITICO, monitor.avaliar(302_000).som)
    }

    @Test
    fun `regra nova ou subida de nivel tocam mesmo silenciado`() {
        confirmarAgua(104.0, 0)
        monitor.silenciar(2_000)
        assertEquals(NivelAlerta.CRITICO, confirmarAgua(109.0, 5_000).som)
        monitor.silenciar(7_000)
        tensao(10.5, 10_000)
        assertEquals(NivelAlerta.CRITICO, tensao(10.5, 13_000).som)
    }

    // ---------------------------------------------------------------- DTC / MIL e comunicação

    @Test
    fun `codigo novo durante a sessao avisa uma vez depois de confirmado`() {
        monitor.observarMil(false, 0, 0)
        assertNull(monitor.observarMil(true, 1, 30_000).som)
        assertTrue(monitor.dtcEmConfirmacao)
        val r = monitor.observarMil(true, 1, 33_000)
        assertEquals(NivelAlerta.ATENCAO, r.som)
        assertEquals("MIL acesa · 1 DTC", r.estado.de(RegraAlerta.DTC)?.valor)
        assertNull(monitor.observarMil(true, 1, 63_000).som)
        assertNull(monitor.avaliar(100_000).estado.de(RegraAlerta.DTC))
        monitor.observarMil(true, 2, 120_000)
        assertEquals(NivelAlerta.ATENCAO, monitor.observarMil(true, 2, 123_000).som)
    }

    @Test
    fun `mil ja acesa no inicio avisa e uma resposta isolada nao`() {
        monitor.observarMil(true, 2, 0)
        assertNotNull(monitor.observarMil(true, 2, 3_000).estado.de(RegraAlerta.DTC))
        monitor.reiniciar()
        monitor.observarMil(false, 0, 0)
        monitor.observarMil(true, 5, 30_000)
        assertTrue(monitor.observarMil(false, 0, 33_000).estado.ativos.isEmpty())
    }

    @Test
    fun `comunicacao perdida e opcional`() {
        agua(90.0, 0)
        assertTrue(monitor.avaliar(60_000).estado.ativos.isEmpty())
        monitor.config = ConfigAlertas(comunicacaoAtiva = true)
        val r = monitor.avaliar(61_000)
        assertEquals(RegraAlerta.COMUNICACAO, r.estado.principal?.regra)
        assertEquals(NivelAlerta.ATENCAO, r.som)
        assertTrue(agua(90.0, 62_000).estado.ativos.isEmpty())
    }

    @Test
    fun `regra desligada encerra o alerta`() {
        confirmarAgua(109.0, 0)
        monitor.config = ConfigAlertas(aguaAtiva = false)
        assertTrue(monitor.avaliar(2_000).estado.ativos.isEmpty())
        assertTrue(confirmarAgua(120.0, 5_000).estado.ativos.isEmpty())
    }

    // ---------------------------------------------------------------- configuração por perfil

    @Test
    fun `cada veiculo guarda os proprios limites`() {
        val perfis = GerenciadorPerfisCarro(Memoria())
        val sw4 = ConfigAlertas(aguaAtencaoC = 98.0, aguaCriticoC = 102.5, canal = CanalAudio.ALARME, volume = 40, comunicacaoAtiva = true)
        perfis.atualizar(1) { it.copy(alertas = sw4) }
        perfis.atualizar(2) { it.copy(alertas = ConfigAlertas(atfAtivo = false)) }

        assertEquals(sw4, perfis.ler(1).alertas)
        assertEquals(ConfigAlertas(atfAtivo = false), perfis.ler(2).alertas)
        assertEquals(ConfigAlertas(), perfis.ler(3).alertas)

        val doSw4 = MonitorAlertasCriticos(perfis.ler(1).alertas)
        val doOutro = MonitorAlertasCriticos(perfis.ler(2).alertas)
        for (m in listOf(doSw4, doOutro)) m.processar(mapOf("0105" to 103.0), null, 0)
        assertEquals(NivelAlerta.CRITICO, doSw4.processar(mapOf("0105" to 103.0), null, 3_000).som)
        assertEquals(NivelAlerta.ATENCAO, doOutro.processar(mapOf("0105" to 103.0), null, 3_000).som)

        perfis.excluir(1)
        assertEquals(ConfigAlertas(), perfis.ler(1).alertas)
    }

    @Test
    fun `padroes e validacao da configuracao`() {
        val p = ConfigAlertas.decodificar(null)
        assertEquals(103.0, p.aguaAtencaoC, 0.0)
        assertEquals(108.0, p.aguaCriticoC, 0.0)
        assertEquals(110.0, p.atfAtencaoC, 0.0)
        assertEquals(120.0, p.atfCriticoC, 0.0)
        assertEquals(11.5, p.tensaoMinV, 0.0)
        assertEquals(15.5, p.tensaoMaxV, 0.0)
        assertTrue(!p.comunicacaoAtiva)
        assertEquals(ErroConfigAlertas.AGUA, p.copy(aguaAtencaoC = 110.0).validar())
        // Limites incoerentes gravados voltam ao padrão só na regra com problema.
        val lido = ConfigAlertas.decodificar(p.copy(aguaAtencaoC = 110.0, atfAtencaoC = 90.0).codificar())
        assertEquals(103.0, lido.aguaAtencaoC, 0.0)
        assertEquals(90.0, lido.atfAtencaoC, 0.0)
    }
}
