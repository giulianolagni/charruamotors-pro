package com.charruamotors.oficina.sensores

import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.obd.log.AmostraLog
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.log.SessaoLog
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.oficina.replay.LeitorCsvLog
import com.charruamotors.oficina.replay.ReducaoSerie
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** v2.5.0: janela deslizante, downsampling, replay de CSV com 4 colunas, presets e categorias. */
class GraficosV250Test {

    private class Memoria : ArmazenamentoChaveValor {
        val mapa = mutableMapOf<String, Any>()
        override fun lerTexto(chave: String) = mapa[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean) = mapa[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) = valores.forEach { (c, v) -> if (v == null) mapa.remove(c) else mapa[c] = v }
    }

    // ---------------------------------------------------------------- buffer e janela deslizante

    @Test
    fun `o buffer cobre a janela de 5 minutos`() {
        val h = HistoricoSessao()
        for (s in 0..400) h.registrar(mapOf("010C" to s.toDouble()), s * 1_000L)
        val q = JanelaDeslizante.recortar(h.amostras(), listOf("010C"), 400_000, JanelaTempo.M5)

        assertEquals(301, q.instantes.size)
        assertEquals(100_000L, q.instantes.first())
        assertEquals(100.0, q.estatistica(0)!!.minimo, 0.0)
    }

    private val amostras = listOf(
        Amostra(0, mapOf("010C" to 4_000.0)),
        Amostra(40_000, mapOf("010C" to 1_000.0, "010B" to 100.0)),
        Amostra(55_000, mapOf("010C" to 2_000.0)),
        Amostra(70_000, mapOf("010C" to 1_500.0, "010B" to 140.0)),
    )

    @Test
    fun `janela deslizante recorta, marca as falhas e calcula so o trecho`() {
        val q = JanelaDeslizante.recortar(amostras, listOf("010C", "010B"), 70_000, JanelaTempo.S30)

        assertEquals(JanelaReplay(40_000, 70_000), q.janela)
        assertEquals(listOf(40_000L, 55_000L, 70_000L), q.instantes.toList())
        assertEquals(2_000.0, q.estatistica(0)!!.maximo, 0.0)
        assertEquals(1_500.0, q.atual(0)!!, 0.0)
        assertTrue(q.series[1][1].isNaN())
        assertEquals(4_000.0, JanelaDeslizante.recortar(amostras, listOf("010C"), 70_000, JanelaTempo.M2).estatistica(0)!!.maximo, 0.0)

        val leitura = q.leitura(1)!!
        assertEquals(55_000L, leitura.tMs)
        assertEquals(2_000.0, leitura.valores["010C"]!!, 0.0)
        assertNull(leitura.valores["010B"])
        assertNull(q.leitura(3))
    }

    @Test
    fun `janela com o fim no passado e corte do zerar deixam as demais amostras de fora`() {
        val pausada = JanelaDeslizante.recortar(amostras, listOf("010C"), 55_000, JanelaTempo.S30)
        assertEquals(listOf(40_000L, 55_000L), pausada.instantes.toList())

        val zerada = JanelaDeslizante.recortar(amostras, listOf("010C"), 70_000, JanelaTempo.M2, desdeMs = 55_001)
        assertEquals(listOf(70_000L), zerada.instantes.toList())
        assertTrue(JanelaDeslizante.recortar(emptyList(), listOf("010C"), 0, JanelaTempo.M2).vazio)
    }

    // ---------------------------------------------------------------- downsampling

    @Test
    fun `com poucas amostras o traco vai inteiro e a falha o interrompe`() {
        val t = longArrayOf(0, 10, 20, 30)
        val v = doubleArrayOf(1.0, 2.0, Double.NaN, 4.0)
        val pontos = ReducaoSerie.reduzir(t, v, 0, 3, JanelaReplay(0, 30), colunas = 100)

        assertEquals(listOf(0, 1, 3), pontos.map { it.indice })
        assertEquals(listOf(true, false, true), pontos.map { it.novoTraco })
    }

    @Test
    fun `reducao preserva picos e vales na ordem do tempo com no maximo 2 pontos por coluna`() {
        val n = 10_000
        val t = LongArray(n) { it.toLong() }
        val v = DoubleArray(n) { 800.0 }
        v[1_234] = 4_500.0
        v[1_240] = 600.0
        v[9_999] = 5_000.0
        val pontos = ReducaoSerie.reduzir(t, v, 0, n - 1, JanelaReplay(0, n - 1L), colunas = 100)
        val indices = pontos.map { it.indice }

        assertTrue(pontos.size <= 2 * 101)
        assertTrue(indices.containsAll(listOf(1_234, 1_240, 9_999)))
        assertEquals(indices.sorted(), indices)
        assertEquals(1, pontos.count { it.novoTraco })
    }

    @Test
    fun `reducao interrompe o traco num trecho sem leitura`() {
        val n = 1_000
        val t = LongArray(n) { it.toLong() }
        val v = DoubleArray(n) { if (it in 400..599) Double.NaN else 1.0 }
        val pontos = ReducaoSerie.reduzir(t, v, 0, n - 1, JanelaReplay(0, n - 1L), colunas = 50)

        assertEquals(listOf(0, 600), pontos.filter { it.novoTraco }.map { it.indice })
        assertTrue(ReducaoSerie.reduzir(t, v, 400, 599, JanelaReplay(400, 599), colunas = 10).isEmpty())
    }

    // ---------------------------------------------------------------- replay de CSV

    @Test
    fun `replay do CSV da viagem resume ate 4 colunas so no trecho visivel`() {
        val colunas = listOf("010C", "010B", "0105", "0106").map(CatalogoTeste::pid)
        val inicio = 1_763_659_805_000L
        val sessao = SessaoLog(
            inicio,
            colunas,
            List(10) { i ->
                AmostraLog(inicio + i * 1_000L, doubleArrayOf(800.0 + 100 * i, 100.0 + i, 80.0 + i, if (i % 2 == 0) -3.1 else Double.NaN))
            },
        )
        val log = LeitorCsvLog.ler(GeradorCsv.gerar(sessao, ZoneOffset.UTC))
        var selecao = emptyList<Int>()
        for (coluna in log.colunasComDados) selecao = AnaliseReplay.alternarSelecao(selecao, coluna)!!
        assertEquals(4, selecao.size)

        val faixa = AnaliseReplay.faixaIndices(log.instantesMs, JanelaReplay(inicio + 2_000, inicio + 5_000))
        val resumo = selecao.map { AnaliseReplay.estatisticas(log.series[it], faixa)!! }

        assertEquals(1_000.0, resumo[0].minimo, 1e-9)
        assertEquals(1_300.0, resumo[0].maximo, 1e-9)
        assertEquals(1_150.0, resumo[0].media, 1e-9)
        assertEquals(103.5, resumo[1].media, 1e-9)
        assertEquals(2, resumo[3].leituras) // o STFT só veio nas amostras pares
    }

    // ---------------------------------------------------------------- presets e categorias

    @Test
    fun `presets prontos usam o que o carro suporta e preferem o que respondeu`() {
        val sw4 = listOf("0105", "TOYOTA_ATF_CAMBIO", "TOYOTA_ATF_ECM", "0104", "010C", "010B", "0111", "0123")
        val diesel = PresetsGrafico.prontos(sw4, setOf("TOYOTA_ATF_ECM"), diesel = true)

        assertEquals(listOf("0105", "TOYOTA_ATF_ECM", "0104"), diesel[0].comandos)
        assertEquals(listOf("010C", "010B", "0111", "0123"), diesel[1].comandos)
        val flex = PresetsGrafico.prontos(listOf("010C", "010B", "0149", "0106", "0123"), emptySet(), diesel = false)
        assertEquals(listOf(PresetsGrafico.TURBO_INJECAO), flex.map { it.nome })
        assertEquals(listOf("010C", "010B", "0149", "0106"), flex.single().comandos)
    }

    @Test
    fun `presets salvos ficam no perfil de cada veiculo e sobrevivem a regravacao`() {
        val memoria = Memoria()
        val perfis = GerenciadorPerfisCarro(memoria)
        val serra = PresetsGrafico.salvar(emptyList(), PresetGrafico("Serra; a|b", listOf("0105", "0104")))!!
        perfis.atualizar(1) { it.copy(presetsGraficos = serra) }
        perfis.atualizar(2) { it.copy(favoritosGraficos = listOf("010C")) }

        val lido = GerenciadorPerfisCarro(memoria).ler(1).presetsGraficos
        assertEquals(listOf(PresetGrafico("Serra  a b", listOf("0105", "0104"))), lido)
        assertTrue(perfis.ler(2).presetsGraficos.isEmpty())
        perfis.excluir(1)
        assertTrue(memoria.mapa.keys.none { it.startsWith(GerenciadorPerfisCarro.prefixo(1)) })
    }

    @Test
    fun `mesmo nome substitui o preset e o limite recusa um novo`() {
        var lista = emptyList<PresetGrafico>()
        for (i in 1..PresetsGrafico.MAX_SALVOS) lista = PresetsGrafico.salvar(lista, PresetGrafico("P$i", listOf("010C")))!!

        assertNull(PresetsGrafico.salvar(lista, PresetGrafico("Outro", listOf("010C"))))
        assertEquals(listOf("010D"), PresetsGrafico.salvar(lista, PresetGrafico("p1", listOf("010D")))!!.first().comandos)
        assertNull(PresetsGrafico.salvar(emptyList(), PresetGrafico("  ", listOf("010C"))))
        assertTrue(PresetsGrafico.decodificar("lixo;|010C;nome|").isEmpty())
    }

    @Test
    fun `categorias dos sensores padrao e de montadora`() {
        fun padrao(c: String) = CategoriaSensor.de(CatalogoTeste.padrao[c]!!)
        assertEquals(setOf(CategoriaSensor.TEMPERATURAS, CategoriaSensor.ADMISSAO), padrao("010F"))
        assertEquals(setOf(CategoriaSensor.COMBUSTIVEL), padrao("0114"))
        assertEquals(setOf(CategoriaSensor.ELETRICA), padrao("ATRV"))
        assertEquals(setOf(CategoriaSensor.MOTOR), padrao("010C"))

        val montadora = CatalogoTeste.estendido.perfilPara("Toyota")!!.pids.associateBy { it.comando }
        assertEquals(setOf(CategoriaSensor.TEMPERATURAS, CategoriaSensor.TRANSMISSAO), CategoriaSensor.de(montadora["TOYOTA_ATF_CAMBIO"]!!))
        assertEquals(setOf(CategoriaSensor.COMBUSTIVEL), CategoriaSensor.de(montadora["TOYOTA_INJ1"]!!))
        assertEquals(setOf(CategoriaSensor.TRANSMISSAO), CategoriaSensor.de(montadora["TOYOTA_MARCHA"]!!))
    }
}
