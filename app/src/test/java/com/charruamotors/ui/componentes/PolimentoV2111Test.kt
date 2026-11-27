package com.charruamotors.ui.componentes

import com.charruamotors.direcao.desempenho.CronometroDesempenho
import com.charruamotors.direcao.desempenho.FaseDesempenho
import com.charruamotors.oficina.bateria.LeituraTensao
import com.charruamotors.oficina.bateria.ReferenciasBateria
import com.charruamotors.oficina.bateria.TesteBateria
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.sistema.atualizacao.InterpretadorReleaseGithub
import com.charruamotors.sistema.atualizacao.RespostaHttp
import com.charruamotors.sistema.atualizacao.VerificadorAtualizacao
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.11.1: referências e vale do gráfico de bateria, notas da atualização e o cronômetro automático. */
class PolimentoV2111Test {

    @Test
    fun `vale da partida marca o primeiro ponto de menor tensao`() {
        val teste = TesteBateria()
        teste.registrarSemRpm()
        val valeEsperado = teste.curva.first { it.volts == 9.1 }
        assertEquals(valeEsperado, teste.pontoVale)
        teste.reiniciar()
        assertEquals(null, teste.pontoVale)
    }

    private fun TesteBateria.registrarSemRpm() {
        comRpm = false
        pular() // repouso
        iniciarCaptura(0)
        listOf(12.5, 12.4, 9.4, 9.1, 9.1, 10.8).forEachIndexed { i, v -> registrar(LeituraTensao.Valida(v), i * 200L) }
    }

    @Test
    fun `linhas de referencia seguem o combustivel e cabem na escala`() {
        assertEquals(listOf(8.8, 13.3, 14.9), ReferenciasBateria.de(diesel = true).map { it.volts })
        assertEquals(listOf(9.2, 13.3, 14.9), ReferenciasBateria.de(diesel = false).map { it.volts })

        // Só o repouso na tela: a escala se estica até as linhas, e a ordem vertical se mantém.
        val (minimo, maximo) = AnaliseReplay.incluirNaEscala(12.0 to 13.0, listOf(8.8, 14.9))
        assertTrue(minimo < 8.8 && maximo > 14.9)
        val y = listOf(8.8, 12.5, 14.9).map { AnaliseReplay.normalizar(it, minimo, maximo) }
        assertEquals(y.sorted(), y)
        assertTrue(y.first() > 0.0 && y.last() < 1.0)
        assertEquals(7.0 to 16.0, AnaliseReplay.incluirNaEscala(7.0 to 16.0, listOf(8.8, 14.9)))
    }

    @Test
    fun `notas do changelog formatadas e com alternativa quando vazias`() {
        val notas = "## Novidades\n\n- **Gráfico** com `vale`\n* veja [aqui](https://x.y)\n\n\n**Full Changelog**: https://github.com/a/b"
        assertEquals("Novidades\n\n• Gráfico com vale\n• veja aqui", InterpretadorReleaseGithub.formatarNotas(notas, "-"))
        assertEquals("sem notas", InterpretadorReleaseGithub.formatarNotas("**Full Changelog**: https://x", "sem notas"))
        assertEquals("sem notas", InterpretadorReleaseGithub.formatarNotas("  ", "sem notas"))
    }

    @Test
    fun `notas vem da API e o limite 403 mantem a release do redirect`() = runTest {
        val release = InterpretadorReleaseGithub.releaseDaTag("v9.0.0")!!
        fun verificador(resposta: RespostaHttp) =
            VerificadorAtualizacao("2.0.0", dispatcher = StandardTestDispatcher(testScheduler)) { resposta }

        val ok = RespostaHttp(200, """{"tag_name":"v9.0.0","body":"- novo"}""")
        assertEquals("- novo", verificador(ok).comNotas(release).notas)
        assertEquals(release.apk, verificador(ok).comNotas(release).apk)
        assertEquals(release, verificador(RespostaHttp(403, "{}")).comNotas(release))
        assertEquals(release, verificador(RespostaHttp(200, """{"tag_name":"v8.0.0","body":"outra"}""")).comNotas(release))
    }

    @Test
    fun `cronometro automatico ignora o pico de 255`() {
        var concluidos = 0
        val cronometro = CronometroDesempenho(aoConcluir0100 = { _, _ -> concluidos++ })
        cronometro.amostrar(0.0, 0)
        cronometro.amostrar(20.0, 1_000)
        cronometro.amostrar(255.0, 1_200)
        assertEquals(FaseDesempenho.MEDINDO, cronometro.estado.value.fase)
        assertEquals(0, concluidos)
        cronometro.amostrar(70.0, 5_000)
        cronometro.amostrar(105.0, 9_000)
        assertEquals(1, concluidos)
    }
}
