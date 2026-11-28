package com.charruamotors.obd.log

import com.charruamotors.domain.faixas.ParametroFaixa
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** v2.13.1: boost nomeado na tabela de sensores e percentual fora da faixa nunca como `0 %`. */
class RelatorioSessaoDetalhesTest {

    @Test
    fun `boost sai com nome amigavel e kPa mesmo fora das colunas do CSV`() {
        val e = EstatisticasViagem()
        listOf(-62.0, 74.0).forEach { e.registrar(DadosObd(mapOf(Pids.PRESSAO_BOOST to it))) }
        val r = ResumoSessao(
            cabecalho = CabecalhoSessao(null, null, null, "Teste", null, 0L),
            fimMs = 60_000L,
            conectou = false,
            motivoFim = "fim",
            protocolo = null,
            camada = null,
            protocoloForcado = null,
            tensaoInicial = null,
            dtcs = null,
            estatisticas = e,
            arquivoCsv = null,
            amostrasCsv = 0,
            avisoCsv = null,
        )
        val txt = buildString { RelatorioSessaoTxt.escreverResumo(r, this, ZoneOffset.UTC) }
        val linha = "Pressão de turbo relativa (boost) [CALC_BOOST]: mín -62 · máx 74 · média 6 kPa (2 leituras)"
        assertTrue(txt, linha in txt)
    }

    @Test
    fun `uma leitura fora da faixa em 443 formata menor que 1 por cento`() {
        val a = AcompanhamentoFaixa(PresetVeiculo.SW4_2013.faixas[ParametroFaixa.TENSAO]!!)
        repeat(442) { a.registrar(14.0) }
        a.registrar(12.0)
        assertEquals("abaixo em < 1 % das leituras", a.situacao)
    }
}
