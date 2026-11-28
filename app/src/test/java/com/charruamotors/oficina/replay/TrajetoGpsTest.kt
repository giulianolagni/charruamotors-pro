package com.charruamotors.oficina.replay

import com.charruamotors.direcao.forcag.ForcaG
import com.charruamotors.obd.log.ColunasCelular
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.log.OpcoesLogCelular
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.gps.AmostraGps
import com.charruamotors.sistema.gps.FiltroPosicaoGps
import com.charruamotors.sistema.gps.PosicaoGps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/** CSV com e sem as colunas do celular, trajeto sincronizado com o cursor e exportação GPX. */
class TrajetoGpsTest {

    private val inicio = 1_795_000_000_000L
    private val ambas = OpcoesLogCelular(posicaoGps = true, forcaG = true)
    private val colunas = listOf(Pids.RPM, Pids.PRESSAO_COLETOR).map(CatalogoTeste::pid) + ColunasCelular.definicoes(ambas)

    private fun csv(vararg linhas: Pair<Long, Map<String, Double>>): String = buildString {
        append(GeradorCsv.BOM).append(GeradorCsv.cabecalho(colunas)).append(GeradorCsv.FIM_DE_LINHA)
        for ((ms, valores) in linhas) {
            append(GeradorCsv.linha(inicio + ms, inicio, colunas, valores, ZoneOffset.UTC)).append(GeradorCsv.FIM_DE_LINHA)
        }
    }

    private fun amostra(rpm: Double, map: Double, lat: Double? = null, lon: Double? = null) =
        mapOf(Pids.RPM to rpm, Pids.PRESSAO_COLETOR to map) +
            ColunasCelular.valores(ambas, lat?.let { PosicaoGps(it, lon!!, altitudeM = 687.4) }, ForcaG(0.123, -0.456))

    /** 0 s sem fix; fix em 1 s, 2 s e 3 s; túnel até 60 s; fix de novo. */
    private val log by lazy {
        LeitorCsvLog.ler(
            csv(
                0L to amostra(800.0, 100.0),
                1_000L to amostra(1500.0, 130.0, -28.2600, -52.4000),
                2_000L to amostra(4200.0, 150.0, -28.2610, -52.4010),
                3_000L to amostra(3000.0, 210.0, -28.2620, -52.4020),
                4_000L to amostra(2000.0, 120.0),
                30_000L to amostra(1900.0, 110.0),
                60_000L to amostra(1800.0, 105.0, -28.3000, -52.4500),
            ),
        )
    }

    // ---------------------------------------------------------------- CSV

    @Test
    fun `cabecalho usa os nomes curtos e a leitura devolve as colunas e os valores`() {
        val texto = csv(0L to amostra(800.0, 100.0, -28.262345, -52.406789))
        assertTrue(texto.lineSequence().first().endsWith(",gps_lat,gps_lon,gps_alt_m,gps_vel_kmh,forca_g_long,forca_g_lat"))

        val lido = LeitorCsvLog.ler(texto)
        fun coluna(comando: String) = lido.colunas.indexOfFirst { it.comando == comando }
        assertEquals(-28.262345, lido.series[coluna(ColunasCelular.GPS_LATITUDE)][0], 1e-9)
        assertEquals(-52.406789, lido.series[coluna(ColunasCelular.GPS_LONGITUDE)][0], 1e-9)
        assertEquals(-0.456, lido.series[coluna(ColunasCelular.FORCA_G_LATERAL)][0], 1e-9)
        assertTrue(lido.series[coluna(ColunasCelular.GPS_VELOCIDADE)][0].isNaN())
        assertEquals("Força G lateral (g)", lido.colunas[coluna(ColunasCelular.FORCA_G_LATERAL)].rotulo)
        // Coordenadas não viram chip de gráfico; altitude e Força G, sim.
        assertTrue(coluna(ColunasCelular.GPS_LATITUDE) !in lido.colunasComDados)
        assertTrue(coluna(ColunasCelular.GPS_ALTITUDE) in lido.colunasComDados)
    }

    @Test
    fun `CSV antigo sem as colunas continua abrindo, sem trajeto`() {
        val antigo = "timestamp_ms,data_hora,tempo_s,Rotação (rpm) [010C]\r\n1000,x,0,800\r\n2000,x,1,900\r\n"
        val lido = LeitorCsvLog.ler(antigo)
        assertEquals(2, lido.amostras)
        assertEquals(listOf(0), lido.colunasComDados)
        assertNull(TrajetoGps.de(lido))
    }

    @Test
    fun `coordenadas vazias, zeradas ou fora do globo nao formam trajeto`() {
        val lido = LeitorCsvLog.ler(csv(0L to amostra(800.0, 100.0), 1_000L to amostra(800.0, 100.0, 0.0, 0.0), 2_000L to amostra(800.0, 100.0, 120.0, 10.0)))
        assertNull(TrajetoGps.de(lido))
    }

    @Test
    fun `filtro da posicao descarta fix velho ou impreciso`() {
        val fix = AmostraGps(10f, 0.5f, 8f, instanteMs = 10_000, latitude = -28.2, longitude = -52.4, altitudeM = 600.0)
        assertEquals(PosicaoGps(-28.2, -52.4, 600.0, 36.0), FiltroPosicaoGps.posicao(fix, 11_000))
        assertNull(FiltroPosicaoGps.posicao(fix, 14_000))
        assertNull(FiltroPosicaoGps.posicao(fix.copy(precisaoHorizontalM = 80f), 10_500))
        assertNull(FiltroPosicaoGps.posicao(fix.copy(latitude = null), 10_500))
    }

    // ---------------------------------------------------------------- sincronização com o cursor

    @Test
    fun `cursor usa a posicao da linha ou a mais proxima no tempo, e nada no tunel`() {
        val trajeto = TrajetoGps.de(log)!!
        assertEquals(listOf(1, 2, 3, 6), trajeto.pontos.map { it.indice })
        assertEquals(2, trajeto.pontoEm(2)!!.indice)
        assertEquals(1, trajeto.pontoEm(0)!!.indice) // 1 s antes do primeiro fix
        assertEquals(3, trajeto.pontoEm(4)!!.indice) // 1 s depois do último fix antes do túnel
        assertNull(trajeto.pontoEm(5)) // 27 s sem posição
        assertNull(trajeto.pontoEm(99))
        assertEquals(listOf(3, 1), trajeto.trechos.map { it.size })
    }

    @Test
    fun `cada pico aponta o ponto da rota em que aconteceu`() {
        val picos = TrajetoGps.de(log)!!.picos(log)
        assertEquals(listOf(Pids.RPM, Pids.PRESSAO_COLETOR), picos.map { it.comando }) // sem rail neste log
        assertEquals(2, picos[0].indice)
        assertEquals(-28.2610, picos[0].ponto.latitude, 1e-9)
        assertEquals(3, picos[1].indice)
        assertEquals(-52.4020, picos[1].ponto.longitude, 1e-9)
    }

    @Test
    fun `projecao poe o norte em cima e mantem o trajeto dentro do quadro`() {
        val trajeto = TrajetoGps.de(log)!!
        val projecao = ProjecaoTrajeto(trajeto.pontos, 400f, 200f, 10f)
        for (p in trajeto.pontos) {
            assertTrue(projecao.x(p) in 9.9f..390.1f)
            assertTrue(projecao.y(p) in 9.9f..190.1f)
        }
        assertTrue(projecao.y(trajeto.pontos.first()) < projecao.y(trajeto.pontos.last()))
    }

    // ---------------------------------------------------------------- GPX

    @Test
    fun `GPX e um XML valido com um segmento por trecho, picos como waypoints e horario UTC`() {
        val trajeto = TrajetoGps.de(log)!!
        val pico = trajeto.picos(log).first()
        val gpx = ExportadorGpx.gerar("log <1> & \"2\".csv", trajeto, tempoAbsoluto = true, listOf(MarcoGpx("Pico RPM: 4.200 rpm", pico.ponto)))

        val documento = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
        assertEquals("gpx", documento.documentElement.tagName)
        assertEquals("1.1", documento.documentElement.getAttribute("version"))
        assertEquals(2, documento.getElementsByTagName("trkseg").length)
        assertEquals(4, documento.getElementsByTagName("trkpt").length)
        val primeiro = documento.getElementsByTagName("trkpt").item(0) as Element
        assertEquals("-28.260000", primeiro.getAttribute("lat"))
        assertEquals("-52.400000", primeiro.getAttribute("lon"))
        assertEquals("687.4", primeiro.getElementsByTagName("ele").item(0).textContent)
        assertEquals(java.time.Instant.ofEpochMilli(inicio + 1_000).toString(), primeiro.getElementsByTagName("time").item(0).textContent)
        val marco = documento.getElementsByTagName("wpt").item(0) as Element
        assertEquals("Pico RPM: 4.200 rpm", marco.getElementsByTagName("name").item(0).textContent)
        assertNotNull(documento.getElementsByTagName("metadata").item(0))
        assertTrue("&lt;1&gt; &amp; &quot;2&quot;" in gpx)
    }

    @Test
    fun `GPX de log sem relogio real sai sem horario e sem repetir o mesmo fix`() {
        val parado = LeitorCsvLog.ler(
            csv(0L to amostra(800.0, 100.0, -28.26, -52.40), 200L to amostra(800.0, 100.0, -28.26, -52.40)),
        )
        val gpx = ExportadorGpx.gerar("parado", TrajetoGps.de(parado)!!, tempoAbsoluto = false)
        assertEquals(1, Regex("<trkpt ").findAll(gpx).count())
        assertTrue("<time>" !in gpx)
    }
}
