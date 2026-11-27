package com.charruamotors.domain.manutencao

import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.laudo.BlocoLaudo
import com.charruamotors.laudo.DadosLaudo
import com.charruamotors.laudo.MontadorLaudo
import com.charruamotors.laudo.OrigemLaudo
import com.charruamotors.sistema.backup.BackupJson
import com.charruamotors.sistema.backup.DadosBackup
import com.charruamotors.sistema.backup.PreferenciasBackup
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** v2.11.0: situação dos itens, odômetro, "Registrar troca agora", backup e laudo. */
class ManutencaoPreventivaTest {

    private val hoje = LocalDate.of(2026, 11, 27)

    private val oleo = ItemManutencao(
        id = 1, nome = "Óleo do motor", kmUltimaTroca = 100_000, dataUltimaTroca = hoje,
        intervaloKm = 10_000, intervaloMeses = 12,
    )

    private class Memoria : ArmazenamentoChaveValor {
        val valores = mutableMapOf<String, Any?>()
        override fun lerTexto(chave: String): String? = valores[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean): Boolean = valores[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            valores.forEach { (k, v) -> if (v == null) this.valores.remove(k) else this.valores[k] = v }
        }
    }

    @Test
    fun `status por km com a margem de dez por cento`() {
        fun status(km: Int) = PlanoManutencao.situacao(oleo, km, hoje).status
        assertEquals(StatusManutencao.EM_DIA, status(108_999))
        assertEquals(StatusManutencao.PROXIMO, status(109_000))
        assertEquals(StatusManutencao.PROXIMO, status(109_999))
        assertEquals(StatusManutencao.VENCIDO, status(110_000))
    }

    @Test
    fun `status por data com a margem de trinta dias e o que vencer primeiro`() {
        fun status(vence: LocalDate, km: Int = 100_000) =
            PlanoManutencao.situacao(oleo.copy(dataUltimaTroca = vence.minusMonths(12)), km, hoje).status
        assertEquals(StatusManutencao.EM_DIA, status(hoje.plusDays(31)))
        assertEquals(StatusManutencao.PROXIMO, status(hoje.plusDays(30)))
        assertEquals(StatusManutencao.VENCIDO, status(hoje))
        // Km vencida com a data em dia.
        assertEquals(StatusManutencao.VENCIDO, status(hoje.plusDays(200), km = 111_000))
        // Sem intervalo nenhum, nunca vence.
        val livre = oleo.copy(intervaloKm = null, intervaloMeses = null)
        assertEquals(StatusManutencao.EM_DIA, PlanoManutencao.situacao(livre, 999_999, hoje.plusYears(20)).status)
    }

    @Test
    fun `odometro soma as viagens, aceita correcao e segue o cadastro novo`() {
        val perfis = GerenciadorPerfisCarro(Memoria())
        perfis.salvar(PerfilCarro(7))
        perfis.salvar(PerfilCarro(9))
        fun km(cadastro: Int, id: Long = 7) = Odometro.atual(perfis.ler(id).odometro, cadastro)

        perfis.sincronizarOdometro(7, 100_000)
        perfis.acumularQuilometros(7, 12.6)
        perfis.acumularQuilometros(7, 0.7)
        perfis.sincronizarOdometro(7, 100_000)
        assertEquals(100_013, km(100_000))
        assertNull("o outro carro não anda junto", perfis.ler(9).odometro)

        perfis.definirOdometro(7, 100_000, 99_500)
        assertEquals(99_500, km(100_000))

        // Abastecimento registrou uma leitura nova do painel: ela passa a valer.
        assertEquals(100_800, km(100_800))
        perfis.sincronizarOdometro(7, 100_800)
        perfis.acumularQuilometros(7, 10.0)
        assertEquals(100_810, km(100_800))
        // Perfil inexistente não é criado pela sincronização.
        perfis.sincronizarOdometro(55, 1_000)
        assertEquals(false, perfis.existe(55))
    }

    @Test
    fun `registrar troca agora zera a contagem e entra no historico do carro`() {
        val perfis = GerenciadorPerfisCarro(Memoria())
        perfis.salvar(PerfilCarro(7, planoManutencao = PlanoManutencao().com(oleo.copy(id = 0, dataUltimaTroca = hoje.minusYears(2)))))
        perfis.sincronizarOdometro(7, 100_000)
        perfis.acumularQuilometros(7, 11_000.0)

        perfis.registrarTroca(7, 1, 100_000, hoje)

        val plano = perfis.ler(7).planoManutencao
        assertEquals(111_000, plano.itens.single().kmUltimaTroca)
        assertEquals(hoje, plano.itens.single().dataUltimaTroca)
        assertEquals(listOf(RegistroTroca(1, "Óleo do motor", 111_000, hoje)), plano.historico)
        assertTrue(plano.pendencias(111_000, hoje).isEmpty())
        // Item removido mantém o histórico, e o próximo não reaproveita o id dele.
        assertEquals(2L, plano.sem(1).com(oleo.copy(id = 0)).itens.single().id)
    }

    @Test
    fun `backup leva plano, odometro, oficina e tecnico e o arquivo antigo continua valendo`() {
        val perfil = PerfilCarro(
            7,
            planoManutencao = PlanoManutencao().com(oleo.copy(id = 0, observacao = "5W30; sintético")).registrarTroca(1, 101_000, hoje),
            odometro = Odometro(100_000, 101_000.4),
        )
        val veiculo = Veiculo(id = 7, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 100_000, tipoCombustivel = TipoCombustivel.DIESEL)
        val dados = DadosBackup(
            geradoEm = "2026-11-27T10:00:00Z",
            veiculos = listOf(veiculo),
            preferencias = PreferenciasBackup(nomeOficina = "Oficina Charrua", nomeTecnico = ""),
            perfisCarros = listOf(perfil),
        )
        assertEquals(dados, BackupJson.desserializar(BackupJson.serializar(dados)))

        // Backup da v2.10: sem os campos novos.
        val raiz = JSONObject(BackupJson.serializar(dados))
        raiz.getJSONObject("preferencias").apply { remove("nomeOficina"); remove("nomeTecnico") }
        raiz.getJSONArray("perfisCarros").getJSONObject(0).getJSONObject("perfil").apply { remove("plano_manutencao"); remove("odometro") }
        val antigo = BackupJson.desserializar(raiz.toString())
        assertNull(antigo.preferencias.nomeOficina)
        assertNull(antigo.preferencias.nomeTecnico)
        assertEquals(PlanoManutencao(), antigo.perfisCarros.single().planoManutencao)
        assertNull(antigo.perfisCarros.single().odometro)
    }

    @Test
    fun `laudo ganha a secao final so com pendencias do carro identificado`() {
        val plano = PlanoManutencao(listOf(oleo, oleo.copy(id = 2, nome = "Filtro de ar", kmUltimaTroca = 110_000)))
        val linhas = plano.linhasDoLaudo(110_500, hoje)
        assertEquals(listOf("Óleo do motor"), linhas.map { it.first })

        val dados = DadosLaudo(origem = OrigemLaudo.LOG_REAL, emitidoEmMs = 0, fonte = "x", observacoes = "obs", manutencao = linhas)
        val secoes = MontadorLaudo.montar(dados).blocos.filterIsInstance<BlocoLaudo.Secao>()
        assertTrue(secoes.last().titulo.startsWith("Manutenção preventiva"))
        assertTrue(MontadorLaudo.montar(dados.copy(manutencao = emptyList())).blocos.filterIsInstance<BlocoLaudo.Secao>().none { "preventiva" in it.titulo })

        val sw4 = Veiculo(id = 7, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 0, tipoCombustivel = TipoCombustivel.DIESEL)
        val nome = "CharruaOBD_20261120_080000_Toyota_SW4.csv"
        assertEquals(sw4, VeiculoDoLog.identificar("ABC-1D23", "outro.txt", listOf(sw4)))
        assertEquals(sw4, VeiculoDoLog.identificar(null, nome, listOf(sw4)))
        assertNull("dois carros iguais: não adivinha", VeiculoDoLog.identificar(null, nome, listOf(sw4, sw4.copy(id = 8, placa = "XYZ1234"))))
        assertNull("a placa do relatório manda", VeiculoDoLog.identificar("ZZZ9999", nome, listOf(sw4)))
    }
}
