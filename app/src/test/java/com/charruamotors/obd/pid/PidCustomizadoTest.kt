package com.charruamotors.obd.pid

import com.charruamotors.carros.PerfilCarro
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.Elm327BluetoothClient
import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd
import com.charruamotors.obd.transporte.ObdTransport
import com.charruamotors.sistema.backup.BackupJson
import com.charruamotors.sistema.backup.DadosBackup
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PidCustomizadoTest {

    private fun pid(equacao: String = "A", modo: String = "221940", cabecalho: String? = "7E1") =
        PidCustomizado.criar("ATF", "ATF", modo, equacao, -40.0, 200.0, "°C", cabecalho)

    @Test
    fun `equacoes com A a D, Signed e divisao por zero`() {
        val bytes = listOf(0xFF, 0x38, 6, 4)
        assertEquals(-200.0, Formula.compilar("Signed(256*A+B)").avaliar(bytes), 0.0)
        assertEquals(-1.0, Formula.compilar("signed(A)").avaliar(bytes), 0.0)
        assertEquals(128.0, Formula.compilar("Signed(256*A+B)").avaliar(listOf(0, 128)), 0.0)
        assertEquals(1.5, Formula.compilar("(Signed(A) + C - D) / (C - D)  + 1").avaliar(bytes), 0.0001 + 1.0)
        // Divisão por zero não lança: a leitura é descartada pela faixa.
        val definicao = pid("A/(B-B)").definicao(6)
        assertTrue(ObdParser.parsePid("62 19 40 0A 01", definicao) !is RespostaObd.Ok)
        for (ruim in listOf("A+", "E*2", "X", "10", "ABS(A)", "{A:1}", "(".repeat(50) + "A" + ")".repeat(50), "-".repeat(100) + "A")) {
            assertThrows(ruim, IllegalArgumentException::class.java) { pid(ruim) }
        }
        // Nunca um serviço que escreve na ECU, nem comandos AT ou multi-frame de identificação.
        for (modo in listOf("04", "2F0101", "3101", "1003", "ATZ", "0902")) {
            assertThrows(modo, IllegalArgumentException::class.java) { pid(modo = modo) }
        }
    }

    @Test
    fun `CSV do Torque aceita as linhas validas e explica as recusadas`() {
        val csv = """
            Name,ShortName,ModeAndPID,Equation,Min,Max,Units,Header
            "Temp, ATF",ATF,0x221940,A-40,-40,200,°C,7E1
            Sem equação,SE,2101,,0,1,,
            Curta,C
            Limpa DTC,CLR,04,A,0,1,,
            Sem faixa,SF,21 82,Signed(256*A+B)/10,,,V,Auto
            Header J1850,HJ,221234,A,0,1,,6C10F1
            Min ruim,MR,2101,A,x,1,,
        """.trimIndent()
        val r = ImportadorPids.importar(csv)
        assertEquals(listOf("Temp ATF", "Sem faixa"), r.pids.map { it.nome })
        assertEquals("7E1", r.pids[0].cabecalho)
        assertEquals(null, r.pids[1].cabecalho)
        assertEquals("2182", r.pids[1].modoPid)
        assertEquals(5, r.erros.size)
        assertTrue(r.erros.first().startsWith("Linha 3"))
        assertEquals(r.pids, ImportadorPids.importar(ImportadorPids.exportar(r.pids)).pids)
    }

    private class Ecu(private val protocolo: String) : ObdTransport {
        val enviados = mutableListOf<String>()

        override suspend fun conectar() = Unit

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            enviados += comando
            return when {
                comando == "ATZ" -> "ELM327 v1.5\r\r"
                comando.startsWith("0100") -> "41001E3F8000\r\r"
                comando == "ATDPN" -> "$protocolo\r\r"
                comando == "ATDP" -> if (protocolo == "A5") "AUTO, ISO 14230-4 (KWP FAST)\r\r" else "ISO 15765-4 (CAN 11/500)\r\r"
                comando == "ATRV" -> "14.1V\r\r"
                comando == "221940" -> "6219405A\r\r"
                comando.startsWith("AT") -> "OK\r\r"
                else -> "NO DATA\r\r"
            }
        }

        override fun fechar() = Unit
    }

    private fun TestScope.cliente(ecu: Ecu) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { ecu },
        dispatcher = StandardTestDispatcher(testScheduler),
        catalogoEstendido = CatalogoTeste.estendido,
        catalogoCascata = CatalogoTeste.cascata,
    )

    @Test
    fun `cabecalho proprio so vai em CAN de 11 bits e o funcional volta`() = runTest {
        for (can in listOf(6, 8)) assertEquals("7E1", pid().definicao(can).estendida!!.cabecalho)
        for (outro in listOf(1, 2, 3, 4, 5, 7, 9, null)) assertEquals("", pid().definicao(outro).estendida!!.cabecalho)

        for (protocolo in listOf("A5", "A6")) {
            val ecu = Ecu(protocolo)
            val obd = cliente(ecu)
            obd.conectar(DispositivoObd("OBDII", "00:11:22:33:44:55"), null).getOrThrow()
            ecu.enviados.clear()
            val chave = pid().chave
            assertEquals(listOf(chave), obd.ativarPidsCustomizados(listOf(pid())).map { it.comando })
            val lido = obd.lerDadosEmTempoReal(10, listOf(chave)).take(1).toList().single()
            assertEquals(90.0, lido[chave]!!, 0.0)
            if (protocolo == "A5") {
                assertTrue(ecu.enviados.toString(), ecu.enviados.none { it.startsWith("ATSH") || it.startsWith("ATCRA") })
            } else {
                assertTrue(ecu.enviados.toString(), "ATSH7E1" in ecu.enviados)
                assertEquals("ATSH7DF", ecu.enviados.last { it.startsWith("ATSH") })
            }
        }
    }

    @Test
    fun `PIDs e escolha manual vao e voltam no backup`() {
        val veiculo = Veiculo(id = 7, marca = "Fiat", modelo = "Uno", ano = 2010, placa = "ABC1D23", kmAtual = 1, tipoCombustivel = TipoCombustivel.FLEX)
        val perfil = PerfilCarro(7, pidsCustomizados = listOf(pid(), pid("B", "2101", null)))
            .comPerfilEscolhido(PerfilCarro.OBD2_GENERICO)
        val json = BackupJson.serializar(DadosBackup(geradoEm = "2026-11-28T09:00:00Z", veiculos = listOf(veiculo), perfisCarros = listOf(perfil)))
        val restaurado = BackupJson.desserializar(json).perfisCarros.single()
        assertEquals(perfil.pidsCustomizados, restaurado.pidsCustomizados)
        assertTrue(restaurado.perfilManual)
        // Entrada corrompida não derruba o perfil.
        assertTrue(PidCustomizado.decodificar("[{\"nome\":\"x\"}, 3]").isEmpty())
    }
}
