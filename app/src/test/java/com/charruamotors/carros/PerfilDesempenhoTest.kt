package com.charruamotors.carros

import com.charruamotors.direcao.desempenho.HistoricoDesempenho
import com.charruamotors.direcao.desempenho.MarcaDesempenho
import com.charruamotors.direcao.desempenho.ModalidadeTeste
import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.domain.calculo.CalibracaoConsumo
import com.charruamotors.domain.calculo.CombustivelCalibracao
import com.charruamotors.sistema.backup.BackupJson
import com.charruamotors.sistema.backup.DadosBackup
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Test

/** v2.6.0: calibração, histórico de tempos e contador do tanque isolados por veículo. */
class PerfilDesempenhoTest {

    private class Memoria : ArmazenamentoChaveValor {
        val valores = mutableMapOf<String, Any?>()
        override fun lerTexto(chave: String): String? = valores[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean): Boolean = valores[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            valores.forEach { (k, v) -> if (v == null) this.valores.remove(k) else this.valores[k] = v }
        }
    }

    private val memoria = Memoria()
    private val perfis = GerenciadorPerfisCarro(memoria)
    private val calibracao = CalibracaoConsumo(1.4, CombustivelCalibracao.ETANOL, 0.92, 4.19, -3.5)

    private fun marca(tempoMs: Long, modalidade: ModalidadeTeste = ModalidadeTeste.V0_100) =
        MarcaDesempenho(modalidade, tempoMs, 100.0, 1_790_000_000_000)

    @Test
    fun `calibracao, tempos e tanque ficam so no veiculo e sobrevivem a releitura`() {
        perfis.atualizar(7) { it.copy(calibracaoConsumo = calibracao) }
        perfis.registrarTempo(7, marca(11_204))
        perfis.registrarTempo(7, marca(9_876))
        perfis.registrarTempo(7, marca(5_100, ModalidadeTeste.V80_120))
        perfis.acumularLitrosTanque(7, 1.5)
        perfis.acumularLitrosTanque(7, 2.0)
        perfis.criarSeAusente(9) { PerfilCarro(9) }

        val relido = GerenciadorPerfisCarro(memoria).ler(7)
        assertEquals(calibracao, relido.calibracaoConsumo)
        assertEquals(listOf(9_876L, 11_204L), relido.historicoDesempenho.melhores(ModalidadeTeste.V0_100).map { it.tempoMs })
        assertEquals(RecordeDesempenho(melhor0100 = 9.88), relido.recorde)
        assertEquals(3.5, relido.litrosTanque, 1e-9)

        val outro = perfis.ler(9)
        assertEquals(CalibracaoConsumo(), outro.calibracaoConsumo)
        assertEquals(HistoricoDesempenho(), outro.historicoDesempenho)
        assertEquals(0.0, outro.litrosTanque, 0.0)

        perfis.excluir(7)
        assertEquals(emptyList<String>(), memoria.valores.keys.filter { it.startsWith("carro.7.") })
    }

    @Test
    fun `contador do tanque nao conta como alteracao do perfil`() {
        perfis.criarSeAusente(7) { PerfilCarro(7) }
        val antes = perfis.alteracoes.value
        perfis.acumularLitrosTanque(7, 0.4)
        assertEquals(antes, perfis.alteracoes.value)
    }

    @Test
    fun `duplicar leva o motor e o preco, nao os tempos, o ajuste nem o tanque`() {
        perfis.atualizar(7) { it.copy(calibracaoConsumo = calibracao, litrosTanque = 12.0) }
        perfis.registrarTempo(7, marca(9_000))
        val copia = perfis.duplicar(7, 9)

        assertEquals(calibracao.copy(ajustePercentual = 0.0), copia.calibracaoConsumo)
        assertEquals(HistoricoDesempenho(), copia.historicoDesempenho)
        assertEquals(0.0, copia.litrosTanque, 0.0)
        assertEquals(1, perfis.ler(7).historicoDesempenho.marcas.size)
    }

    @Test
    fun `historico guarda so os 10 melhores por modalidade e o backup o leva`() {
        var historico = HistoricoDesempenho()
        (1..15).forEach { historico = historico.com(marca(20_000L - it * 100)) }
        assertEquals(10, historico.marcas.size)
        assertEquals(18_500L, historico.marcas.first().tempoMs)

        val perfil = PerfilCarro(7, calibracaoConsumo = calibracao, historicoDesempenho = historico, litrosTanque = 8.25)
        val veiculo = Veiculo(id = 7, marca = "Chevrolet", modelo = "Onix", ano = 2016, placa = "ONX1A16", kmAtual = 1, tipoCombustivel = TipoCombustivel.FLEX)
        val json = BackupJson.serializar(DadosBackup(geradoEm = "2026-11-26T10:00:00Z", veiculos = listOf(veiculo), perfisCarros = listOf(perfil)))
        assertEquals(perfil, BackupJson.desserializar(json).perfisCarros.single())
    }
}
