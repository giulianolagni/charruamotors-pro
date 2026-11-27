package com.charruamotors.obd.log

import com.charruamotors.carros.CamadaPreferida
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.laudo.DadosLaudo
import com.charruamotors.laudo.DtcLaudo
import com.charruamotors.laudo.DiagnosticoLaudo
import com.charruamotors.laudo.FaixaLida
import com.charruamotors.laudo.LaudoDeLog
import com.charruamotors.laudo.OrigemLaudo
import com.charruamotors.laudo.ResumoTecnicoLaudo
import com.charruamotors.oficina.bicos.AvaliacaoBicos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** v2.10.0: sessões agrupadas do Histórico, laudo combinado e o bloco dos bicos por perfil. */
class AgrupadorSessoesTest {

    private data class Log(override val nome: String, override val modificadoMs: Long = 0) : ItemLog

    @Test
    fun `junta csv e txt da sessao, mantem orfaos e ordena da mais recente`() {
        val csv = Log("CharruaOBD_20261120_080000_Toyota_SW4.csv")
        val txt = Log("CharruaOBD_20261120_080000_Toyota_SW4.txt")
        val soTxt = Log("CharruaOBD_20261122_143005_SemVeiculo.txt")
        val manual = Log("charrua_obd_2026-11-21_18-45-10.csv")
        val sessoes = OrganizadorLogs.ordenar(AgrupadorSessoes.agrupar(listOf(csv, manual, txt, soTxt)), ZoneOffset.UTC)

        assertEquals(listOf(AgrupadorSessoes.TITULO_SEM_VEICULO, AgrupadorSessoes.TITULO_MANUAL, "Toyota SW4"), sessoes.map { it.titulo })
        assertEquals(listOf(null, manual, csv), sessoes.map { it.csv })
        assertEquals(listOf(soTxt, null, txt), sessoes.map { it.txt })
    }

    @Test
    fun `nomes repetidos nao somem e o simulado nao se mistura com o real`() {
        val nome = "CharruaOBD_20261120_080000_Toyota_SW4"
        val sessoes = AgrupadorSessoes.agrupar(
            listOf(Log("$nome.csv", 1), Log("$nome.csv", 2), Log("$nome.txt"), Log("CharruaOBD_SIMULADO_20261120_080000_Toyota_SW4.txt")),
        )
        assertEquals(3, sessoes.size)
        assertEquals(4, sessoes.sumOf { it.arquivos.size })
        assertEquals(1, sessoes.count { OrganizadorLogs.ehSimulado(it.nome) })
    }

    @Test
    fun `laudo combinado usa o relatorio e completa o resumo com o csv`() {
        val doTxt = DadosLaudo(
            origem = OrigemLaudo.LOG_REAL,
            emitidoEmMs = 0,
            fonte = "a.txt",
            diagnostico = DiagnosticoLaudo(ativos = listOf(DtcLaudo("P0300", "Falha"))),
            resumo = ResumoTecnicoLaudo(rpmMaximo = 3420.0),
        )
        val doCsv = DadosLaudo(
            origem = OrigemLaudo.LOG_REAL,
            emitidoEmMs = 0,
            fonte = "a.csv",
            resumo = ResumoTecnicoLaudo(rpmMaximo = 3000.0, segundosParado = 12.0, agua = FaixaLida(40.0, 90.0)),
        )
        val junto = LaudoDeLog.daSessao(doTxt, doCsv)!!

        assertEquals(doTxt.diagnostico, junto.diagnostico)
        assertEquals(3420.0, junto.resumo.rpmMaximo!!, 0.0)
        assertEquals(12.0, junto.resumo.segundosParado!!, 0.0)
        assertEquals(FaixaLida(40.0, 90.0), junto.resumo.agua)
        assertEquals("a.txt + a.csv", junto.fonte)
        assertTrue(LaudoDeLog.daSessao(doTxt, doCsv.copy(origem = OrigemLaudo.SIMULADO))!!.simulado)
        assertEquals(doCsv, LaudoDeLog.daSessao(null, doCsv))
        assertNull(LaudoDeLog.daSessao(null, null))
    }

    @Test
    fun `bicos so no perfil 1KD-FTV com recursos da montadora`() {
        fun visivel(preset: String, camada: CamadaPreferida = CamadaPreferida.AUTO): Boolean {
            val perfil = PerfilCarro(1, perfilFaixasId = preset, camada = camada)
            val veiculo = com.charruamotors.domain.model.Veiculo(
                id = 1, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23",
                tipoCombustivel = com.charruamotors.domain.model.TipoCombustivel.DIESEL, kmAtual = 0,
            )
            return AvaliacaoBicos.disponivel(perfil, perfil.resolver(veiculo))
        }
        assertTrue(visivel(PresetVeiculo.ID_SW4_2013))
        assertTrue(visivel(PresetVeiculo.ID_SW4_2013, CamadaPreferida.MODO_21))
        assertFalse(visivel(PresetVeiculo.ID_SW4_2013, CamadaPreferida.OBD2))
        assertFalse(visivel(PresetVeiculo.ID_CRUZE_2018))
        assertFalse(visivel(PerfilCarro.OBD2_GENERICO))
    }
}
