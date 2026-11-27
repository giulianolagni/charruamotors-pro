package com.charruamotors.obd

import com.charruamotors.direcao.hud.EssenciaisHud
import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.PresetHud
import com.charruamotors.domain.alertas.ConfigAlertas
import com.charruamotors.domain.alertas.MonitorAlertasCriticos
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.transporte.ObdTransport
import com.charruamotors.oficina.sensores.FocoSensores
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** v2.5.0: priorização do polling pelos sensores das telas de inspeção. */
class FocoPollingTest {

    private val suportados = listOf(
        "0104", "0105", "0106", "0107", "010B", "010C", "010D", "010F", "0110", "0111", "0133", "015C", "ATRV", "GM_X", "7E1_ATF",
    )

    private fun escalonador(custoDe: (List<String>) -> Int = { it.size }) = EscalonadorPolling(
        candidatosRapidos = Pids.candidatosFilaRapida(consumoPorMaf = true),
        leves = setOf("7E1_ATF"),
        custoDe = custoDe,
        exigeCabecalho = { it == "GM_X" },
    ).apply { atualizarSuportados(suportados) }

    private fun EscalonadorPolling.ciclos(n: Int) = List(n) { proximoCiclo() }

    private val essenciais = setOf(Pids.RPM, Pids.VELOCIDADE, "0105", "ATRV")

    @Test
    fun `sensores visiveis ocupam em todo ciclo so os comandos que a fila rapida liberou`() {
        val e = escalonador()
        e.definirFoco(FocoPolling(visiveis = setOf("0104", "0106"), essenciais = essenciais))

        e.ciclos(30).forEach { ciclo ->
            // MAP e MAF fora da tela saem da fila rápida e cedem os 2 comandos aos visíveis.
            assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE, "0104", "0106"), ciclo.filter { it != "7E1_ATF" }.take(4))
        }
    }

    @Test
    fun `um ciclo com foco nunca envia mais comandos que o normal`() {
        val telas = listOf(
            emptySet(),
            setOf("010C"),
            setOf("010C", "010B", "010D", "0110"),
            setOf("0104", "0105", "0106", "0107", "010F", "0111", "0133", "015C"),
            suportados.toSet(),
        )
        // Sem lote (1 comando por PID) e com lote (a fila rápida inteira num comando só).
        for (custo in listOf<(List<String>) -> Int>({ it.size }, { if (it.isEmpty()) 0 else 1 })) {
            val normal = escalonador(custo).ciclos(60)
            for (tela in telas) {
                val e = escalonador(custo)
                e.definirFoco(FocoPolling(tela, essenciais))
                e.ciclos(60).forEachIndexed { i, ciclo ->
                    val rapidos = ciclo.filter { it in e.filaRapida }
                    val rapidosNormais = normal[i].filter { it in e.filaRapida }
                    val comFoco = custo(rapidos) + (ciclo.size - rapidos.size)
                    val semFoco = custo(rapidosNormais) + (normal[i].size - rapidosNormais.size)
                    assertTrue("tela $tela, ciclo $i: $ciclo x ${normal[i]}", comFoco <= semFoco)
                }
            }
        }
    }

    @Test
    fun `PID de montadora visivel so entra na vez da fila lenta e o cambio mantem a cadencia`() {
        val e = escalonador()
        e.definirFoco(FocoPolling(visiveis = setOf("GM_X", "0104"), essenciais = essenciais))
        val ciclos = e.ciclos(48)

        ciclos.forEachIndexed { i, ciclo ->
            if (i % EscalonadorPolling.CICLOS_POR_LENTO != 0) assertFalse("ciclo $i: $ciclo", "GM_X" in ciclo)
            assertEquals(i % EscalonadorPolling.CICLOS_POR_LEVE == 0, "7E1_ATF" in ciclo)
        }
        assertTrue(ciclos.any { "GM_X" in it })
    }

    @Test
    fun `essenciais seguem atualizados e o resto continua sendo lido devagar`() {
        val e = escalonador()
        e.definirFoco(FocoPolling(visiveis = setOf("0104"), essenciais = essenciais, apoio = setOf("0110")))
        val lidos = e.ciclos(120).flatten()
        fun vezes(c: String) = lidos.count { it == c }

        assertEquals(120, vezes("0104"))
        assertTrue("água ${vezes("0105")} x fundo ${vezes("010F")}", vezes("0105") > vezes("010F") * 3)
        // Nada suportado fica de fora (o log e o Painel não param).
        assertEquals(suportados.toSet(), lidos.toSet())
    }

    @Test
    fun `sem foco o polling volta exatamente ao normal`() {
        val e = escalonador()
        e.definirFoco(FocoPolling(setOf("0104")))
        e.ciclos(7)
        e.definirFoco(null)
        val depois = e.ciclos(6)

        assertTrue(depois.all { it.containsAll(listOf("010C", "010B", "010D", "0110")) })
    }

    @Test
    fun `foco soma visiveis, alertas ligados, HUD e as entradas dos calculados`() {
        val monitor = MonitorAlertasCriticos(ConfigAlertas(atfAtivo = false))
        val foco = FocoSensores.montar(
            visiveis = listOf(Pids.RELACAO_AF_REAL),
            alertas = monitor.chavesVigiadas,
            hud = EssenciaisHud.comandos(ConfigHud(preset = PresetHud.ESPORTIVO)),
            suportados = setOf(Pids.MAF),
        )

        assertTrue(Pids.LAMBDA_BANDA_LARGA in foco.visiveis && Pids.PERCENTUAL_ETANOL in foco.visiveis)
        assertTrue(foco.essenciais.containsAll(listOf("0105", "ATRV", Pids.RPM, Pids.VELOCIDADE, Pids.PRESSAO_COLETOR)))
        assertFalse(Pids.COMANDOS_ATF.first() in foco.essenciais)
        assertEquals(setOf(Pids.MAF, Pids.CONSUMO_ECU), foco.apoio)
        // Sem MAF o consumo é por speed-density.
        assertTrue(Pids.PRESSAO_COLETOR in FocoSensores.montar(emptyList(), emptySet(), emptySet(), emptySet()).apoio)
    }

    // ---------------------------------------------------------------- cliente ELM327 v2.1

    private class Ecu(private val respostas: Map<String, String>) : ObdTransport {
        val enviados = mutableListOf<String>()
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            return respostas[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private fun TestScope.cliente(t: Ecu) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
        catalogoEstendido = CatalogoTeste.estendido,
        catalogoCascata = CatalogoTeste.cascata,
    )

    // ---------------------------------------------------------------- prioridade (v2.6.0)

    @Test
    fun `prioridade le so RPM e velocidade, vigia 1 alerta por vez e devolve o ciclo normal`() {
        val e = escalonador()
        val normal = escalonador().ciclos(24)
        e.definirPrioridade(PrioridadePolling(PrioridadePolling.ACELERACAO, vigiados = listOf("0105", "ATRV", "GM_X", "7E1_ATF"), ciclosPorVigiado = 5))
        val vigiando = e.ciclos(20)

        vigiando.forEachIndexed { i, ciclo ->
            assertEquals(listOf(Pids.RPM, Pids.VELOCIDADE), ciclo.takeLast(2))
            // PID de montadora e do câmbio nunca entram: custariam um ATSH no meio do teste.
            assertEquals("ciclo $i: $ciclo", if (i % 5 == 0) 3 else 2, ciclo.size)
        }
        assertEquals(setOf("0105", "ATRV"), vigiando.filter { it.size == 3 }.map { it.first() }.toSet())

        // Alerta em confirmação continua em todo ciclo, mesmo medindo.
        e.definirPrioridade(PrioridadePolling(PrioridadePolling.ACELERACAO, urgentes = listOf("0105")))
        assertEquals(listOf("0105", Pids.RPM, Pids.VELOCIDADE), e.proximoCiclo())

        e.definirPrioridade(null)
        val depois = e.ciclos(24)
        assertTrue(depois.all { it.containsAll(listOf("010C", "010B", "010D", "0110")) })
        assertEquals(normal.flatten().toSet(), depois.flatten().toSet())
    }

    @Test
    fun `no ELM327 v2_1 a prioridade pede so o lote de RPM e velocidade e depois volta ao normal`() = runTest {
        val t = Ecu(
            mapOf(
                "ATZ" to "\r\rELM327 v2.1\r\r",
                "0100" to "41001E3F8000\r\r",
                "01001" to "41001E3F8000\r\r",
                "ATDP" to "AUTO, \r\r",
                "ATDPN" to "A6\r\r",
                "ATRV" to "14.1V\r\r",
                "010C0D" to "410C0FA00D32\r\r",
                "010B10" to "410B32100FA0\r\r",
            ),
        )
        val obd = cliente(t)
        obd.conectar(DispositivoObd("OBDII", "00:11:22:33:44:55"), "Chevrolet Onix").getOrThrow()
        suspend fun comandosEm(ciclos: Int): List<String> {
            val de = t.enviados.size
            obd.lerTelemetria().take(ciclos).toList()
            return t.enviados.drop(de)
        }
        obd.definirPrioridadePolling(PrioridadePolling(PrioridadePolling.ACELERACAO))
        assertEquals(List(6) { "010C0D" }, comandosEm(6).filterNot { it.startsWith("AT") })

        obd.definirPrioridadePolling(null)
        val depois = comandosEm(6)
        assertTrue("$depois", depois.count { it == "010B10" } == 6)
    }

    @Test
    fun `no ELM327 v2_1 o foco mantem o lote e nao aumenta os comandos`() = runTest {
        val t = Ecu(
            mapOf(
                "ATZ" to "\r\rELM327 v2.1\r\r",
                "0100" to "41001E3F8000\r\r",
                "01001" to "41001E3F8000\r\r",
                "ATDP" to "AUTO, \r\r",
                "ATDPN" to "A6\r\r",
                "ATRV" to "14.1V\r\r",
                "010C0D" to "410C0FA00D32\r\r",
                "010B10" to "410B32100FA0\r\r", // 2º lote do ciclo normal (MAP + MAF)
            ),
        )
        val obd = cliente(t)
        obd.conectar(DispositivoObd("OBDII", "00:11:22:33:44:55"), "Chevrolet Onix").getOrThrow()
        assertTrue(obd.usaLoteMultiPid)
        suspend fun comandosEm(ciclos: Int): List<String> {
            val de = t.enviados.size
            obd.lerTelemetria().take(ciclos).toList()
            return t.enviados.drop(de)
        }
        val normal = comandosEm(12)
        obd.definirFocoPolling(FocoPolling(setOf("0105", "0104"), setOf(Pids.RPM, Pids.VELOCIDADE)))
        val comFoco = comandosEm(12)

        assertEquals(12, comFoco.count { it == "010C0D" })
        assertTrue("${comFoco.size} x ${normal.size}", comFoco.size <= normal.size)
        assertTrue(comFoco.count { it.startsWith("0105") } > normal.count { it.startsWith("0105") })
    }
}
