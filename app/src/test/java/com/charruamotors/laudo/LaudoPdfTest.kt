package com.charruamotors.laudo

import com.charruamotors.domain.calculo.EstatisticasPercurso
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.log.CabecalhoSessao
import com.charruamotors.obd.log.DtcsSessao
import com.charruamotors.obd.log.EstatisticasViagem
import com.charruamotors.obd.log.RelatorioSessaoTxt
import com.charruamotors.obd.log.ResumoSessao
import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.replay.LeitorCsvLog
import com.charruamotors.oficina.sensores.MinMax
import com.charruamotors.obd.DadosObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LaudoPdfTest {

    private val zona = ZoneId.of("America/Sao_Paulo")
    private val medidor = MedidorTexto { texto, estilo -> texto.length * estilo.tamanho * 0.5f }

    private fun dados(origem: OrigemLaudo = OrigemLaudo.CONEXAO_REAL) =
        DadosLaudo(origem = origem, emitidoEmMs = 0, fonte = "sessão em curso")

    @Test
    fun veiculoSemVinNemAtfSaiMarcadoComoAusente() {
        val resumo = LaudoDaSessao.resumo(
            mapOf(Pids.TEMPERATURA_MOTOR to MinMax(40.0, 92.0), Pids.VELOCIDADE to MinMax(0.0, 255.0)),
            EstatisticasPercurso(velocidadeMaximaKmh = 112.0),
        )
        val blocos = MontadorLaudo.montar(dados().copy(resumo = resumo), zona).blocos
        fun campo(rotulo: String) = blocos.filterIsInstance<BlocoLaudo.Campo>().single { it.rotulo == rotulo }.valor

        assertEquals(MontadorLaudo.NAO_INFORMADO, campo("VIN (chassi)"))
        assertEquals(MontadorLaudo.SEM_LEITURA, campo("Temperatura do ATF"))
        assertEquals("mín 40 °C · máx 92 °C", campo("Temperatura da água"))
        // O 010D = FF nunca vira velocidade máxima.
        assertEquals("112 km/h", campo("Velocidade máxima"))
        assertTrue(blocos.filterIsInstance<BlocoLaudo.Campo>().none { it.rotulo == "Placa" })
    }

    @Test
    fun listaLongaDeDtcsPaginaSemPerderCodigosComAFaixaEmTodasAsPaginas() {
        val codigos = (1..150).map { DtcLaudo("P%04d".format(it), "Falha de teste com descrição longa ".repeat(3)) }
        val doc = MontadorLaudo.montar(
            dados(OrigemLaudo.SIMULADO).copy(diagnostico = DiagnosticoLaudo(ativos = codigos), observacoes = "x".repeat(400)),
            zona,
        )
        val paginas = PaginadorLaudo.paginar(doc, medidor)

        assertTrue(paginas.size > 2)
        paginas.forEachIndexed { i, pagina ->
            assertTrue(pagina.linhas.any { it.texto == MontadorLaudo.FAIXA_SIMULADO })
            assertEquals("Página ${i + 1} de ${paginas.size}", pagina.linhas.last().texto)
            assertTrue(pagina.linhas.all { it.topo + it.estilo.altura <= PaginadorLaudo.ALTURA - PaginadorLaudo.MARGEM + 12f })
            // Nenhum título sozinho no pé da página.
            val ultimaDoCorpo = pagina.linhas[pagina.linhas.size - 2]
            assertTrue(ultimaDoCorpo.estilo != EstiloLaudo.SECAO && ultimaDoCorpo.estilo != EstiloLaudo.SUBTITULO)
        }
        val texto = paginas.flatMap { it.linhas }.map { it.texto }
        assertTrue(codigos.all { dtc -> texto.any { it.startsWith(dtc.codigo) } })
    }

    @Test
    fun soAConexaoBluetoothConectadaDispensaAFaixaDeSimulado() {
        assertEquals(OrigemLaudo.CONEXAO_REAL, LaudoDaSessao.origem(ModoObd.BLUETOOTH, conectado = true))
        assertEquals(OrigemLaudo.SIMULADO, LaudoDaSessao.origem(ModoObd.BLUETOOTH, conectado = false))
        assertEquals(OrigemLaudo.SIMULADO, LaudoDaSessao.origem(ModoObd.SIMULADO, conectado = true))
        assertNull(MontadorLaudo.montar(dados(), zona).faixaDestaque)
        assertEquals(MontadorLaudo.FAIXA_SIMULADO, MontadorLaudo.montar(dados(OrigemLaudo.SIMULADO), zona).faixaDestaque)

        val csv = LeitorCsvLog.ler("timestamp_ms,Velocidade (km/h) [010D]\n0,10\n1000,255\n")
        val doLog = LaudoDeLog.doCsv("charrua_obd_SIMULADO_2026-11-20_14-30-05.csv", csv, 0)
        assertEquals(OrigemLaudo.SIMULADO, doLog.origem)
        assertEquals(10.0, doLog.resumo.velocidadeMaximaKmh!!, 0.0)
        assertNull(doLog.resumo.atf)
    }

    /** O leitor acompanha o formato que o próprio app grava. */
    @Test
    fun relatorioTxtDaSessaoViraLaudo() {
        val estatisticas = EstatisticasViagem()
        estatisticas.registrar(DadosObd(mapOf(Pids.RPM to 3420.0, Pids.TEMPERATURA_MOTOR to 91.0, Pids.TENSAO_BATERIA to 14.1)))
        val resumo = ResumoSessao(
            cabecalho = CabecalhoSessao("Toyota SW4 2013", "Toyota SW4", "ABC-1234", "Manual", "ELM", 0),
            fimMs = 3_725_000,
            conectou = true,
            motivoFim = "Desconectado",
            protocolo = "ISO 15765-4 (CAN 11/500)",
            camada = "OBD2 Padrão",
            protocoloForcado = null,
            tensaoInicial = 12.4,
            dtcs = DtcsSessao(listOf(DtcLido("P0300", "Falha de ignição")), emptyList(), null),
            estatisticas = estatisticas,
            arquivoCsv = null,
            amostrasCsv = 0,
            avisoCsv = null,
            alertas = listOf("CRÍTICO · ÁGUA QUENTE 110 °C"),
        )
        val txt = buildString { RelatorioSessaoTxt.escreverResumo(resumo, this, zona) }

        val laudo = LaudoDeLog.doRelatorio("CharruaOBD_x.txt", txt.lineSequence(), 0)!!

        assertEquals(OrigemLaudo.LOG_REAL, laudo.origem)
        assertEquals("Toyota SW4 2013", laudo.veiculo.perfil)
        assertEquals("ABC-1234", laudo.veiculo.placa)
        assertNull(laudo.veiculo.vin)
        assertEquals(listOf(DtcLaudo("P0300", "Falha de ignição")), laudo.diagnostico.ativos)
        assertEquals(emptyList<DtcLaudo>(), laudo.diagnostico.pendentes)
        assertNull(laudo.diagnostico.permanentes)
        assertEquals(3420.0, laudo.resumo.rpmMaximo!!, 0.0)
        assertEquals(FaixaLida(14.1, 14.1), laudo.resumo.tensao)
        assertEquals(3725.0, laudo.resumo.duracaoSegundos!!, 0.0)
        assertNull(laudo.resumo.atf)
        assertEquals(listOf("CRÍTICO · ÁGUA QUENTE 110 °C"), laudo.alertas)
    }
}
