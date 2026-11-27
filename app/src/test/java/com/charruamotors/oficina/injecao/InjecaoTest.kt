package com.charruamotors.oficina.injecao

import com.charruamotors.laudo.DadosLaudo
import com.charruamotors.laudo.MontadorLaudo
import com.charruamotors.laudo.OrigemLaudo
import com.charruamotors.laudo.BlocoLaudo
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.EscalonadorPolling
import com.charruamotors.obd.FreezeFrame
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.RespostaObd
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.sensores.FocoSensores
import com.charruamotors.sistema.unidades.ConversorUnidades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InjecaoTest {

    // ---------------------------------------------------------------- fuel trims

    @Test
    fun `trim total soma curto e longo prazo e classifica nas faixas`() {
        assertEquals(10.0, AnaliseMistura.total(4.0, 6.0)!!, 1e-9)
        assertNull("falta o LTFT suportado", AnaliseMistura.total(4.0, null))
        assertEquals(4.0, AnaliseMistura.total(4.0, null, temLtft = false)!!, 1e-9)

        assertEquals(ClasseTrim.NORMAL, AnaliseMistura.classificar(8.0))
        assertEquals(ClasseTrim.NORMAL, AnaliseMistura.classificar(-8.0))
        assertEquals(ClasseTrim.ATENCAO_POBRE, AnaliseMistura.classificar(15.0))
        assertEquals(ClasseTrim.ATENCAO_RICA, AnaliseMistura.classificar(-8.1))
        assertEquals(ClasseTrim.CRITICO_POBRE, AnaliseMistura.classificar(15.1))
        assertEquals(ClasseTrim.CRITICO_RICA, AnaliseMistura.classificar(-15.1))

        assertEquals(RegimeMotor.LENTA, AnaliseMistura.regime(800.0, 0.0, 20.0))
        assertEquals(RegimeMotor.CARGA, AnaliseMistura.regime(1_800.0, 60.0, 20.0))
        assertEquals(RegimeMotor.CARGA, AnaliseMistura.regime(1_500.0, 30.0, 46.0))
        assertNull(AnaliseMistura.regime(1_500.0, 30.0, 30.0))
        assertNull(AnaliseMistura.regime(0.0, 0.0, 0.0))
    }

    @Test
    fun `diagnostico diferencial compara lenta e carga`() {
        assertTrue("entrada de ar falsa" in AnaliseMistura.dica(14.0, 2.0))
        assertTrue("alimentação" in AnaliseMistura.dica(9.0, 18.0))
        assertTrue("alimentação" in AnaliseMistura.dica(1.0, 12.0))
        assertTrue("Rica constante" in AnaliseMistura.dica(-12.0, -11.0))
        assertTrue("dentro do esperado" in AnaliseMistura.dica(3.0, -2.0))
        assertTrue("Meça também em carga" in AnaliseMistura.dica(12.0, null))
    }

    // ---------------------------------------------------------------- sondas

    /** 40 s de leituras a cada 1 s. */
    private fun analisar(agua: Double, s1: (Int) -> Double, s2: (Int) -> Double): DiagnosticoSondas {
        val analise = AnaliseSondas(TipoSondaPre.ESTREITA, temPos = true, diesel = false)
        var r = DiagnosticoSondas()
        repeat(40) { r = analise.registrar(it * 1_000L, agua, 850.0, s1(it), s2(it)) }
        return r
    }

    private val oscilante = { i: Int -> if (i % 2 == 0) 0.15 else 0.8 }

    @Test
    fun `pos estavel e normal e pos espelhando indica catalisador`() {
        val bom = analisar(90.0, oscilante) { 0.62 }
        assertEquals(SituacaoSondaPre.OSCILANDO, bom.pre)
        assertEquals(SituacaoCatalisador.NORMAL, bom.catalisador)

        val ruim = analisar(90.0, oscilante, oscilante)
        assertEquals(SituacaoCatalisador.ESPELHANDO, ruim.catalisador)
    }

    @Test
    fun `sonda travada so e apontada com o motor aquecido`() {
        val quente = analisar(80.0, { 0.82 }) { 0.6 }
        assertEquals(SituacaoSondaPre.TRAVADA_RICA, quente.pre)
        assertEquals("sem S1 ativa não há conclusão do catalisador", SituacaoCatalisador.FORA_DO_USUAL, quente.catalisador)

        val frio = analisar(74.0, { 0.82 }) { 0.6 }
        assertEquals(BloqueioSondas.MOTOR_FRIO, frio.bloqueio)
        assertNull(frio.pre)
    }

    // ---------------------------------------------------------------- suporte e polling

    private val gasolina = setOf("010C", "010D", "010B", "0110", "0105", "0104", "0106", "0107", "0114", "0115", "012F", "ATRV")

    @Test
    fun `diesel e modo 21 degradam para nao suportado sem pedir PIDs`() {
        val diesel = SuporteInjecao.de(setOf("010C", "010D", "0105", "0104"), ModoComunicacaoEcu.OBD2_PADRAO)
        assertTrue(!diesel.banco1.suportado && !diesel.banco2.suportado && diesel.sondaPre == null && diesel.sondaPos == null)
        assertTrue(diesel.comandos.isEmpty())

        val sw4 = SuporteInjecao.de(gasolina, ModoComunicacaoEcu.MODO_21)
        assertTrue(sw4.comandos.isEmpty())

        val banco1 = SuporteInjecao.de(gasolina, ModoComunicacaoEcu.OBD2_PADRAO)
        assertEquals(setOf("0106", "0107", "0114", "0115", "0105", "0104"), banco1.comandos)
        assertTrue(!banco1.banco2.suportado)
    }

    @Test
    fun `foco prioriza trims e sondas sem alongar o ciclo e o polling volta ao sair`() {
        val suporte = SuporteInjecao.de(gasolina, ModoComunicacaoEcu.OBD2_PADRAO)
        fun contar(escalonador: EscalonadorPolling) = List(12) { escalonador.proximoCiclo() }
        val escalonador = EscalonadorPolling().apply { atualizarSuportados(gasolina) }
        val normal = contar(escalonador).flatten().count { it in suporte.comandos }

        escalonador.definirFoco(FocoSensores.montar(suporte.comandos, emptySet(), emptySet(), gasolina))
        val focado = contar(escalonador)
        assertTrue(focado.flatten().count { it in suporte.comandos } > normal * 2)
        assertTrue(focado.all { it.size <= escalonador.filaRapida.size + 1 })

        escalonador.definirFoco(null)
        assertTrue(contar(escalonador).all { it.containsAll(escalonador.filaRapida) })
    }

    @Test
    fun `controlador separa as medias por regime so com a tela aberta e leva o resumo ao laudo`() {
        var relogio = 0L
        val c = ControladorInjecao { relogio }
        fun amostrar(rpm: Double, velocidade: Double, stft: Double, n: Int) = repeat(n) {
            relogio += 500
            c.amostrar(DadosObd(mapOf("010C" to rpm, "010D" to velocidade, "0105" to 90.0, "0106" to stft, "0107" to 6.0)))
        }
        amostrar(800.0, 0.0, 8.0, 10)
        assertNull("fechada, não acumula", c.resumo())

        c.abrir(SuporteInjecao.de(gasolina, ModoComunicacaoEcu.OBD2_PADRAO), motorDiesel = false)
        amostrar(800.0, 0.0, 8.0, 10)
        amostrar(2_500.0, 60.0, -4.0, 10)
        c.fechar()

        val banco = c.resumo()!!.banco1!!
        assertEquals(14.0, banco.lenta!!.media, 1e-9)
        assertEquals(2.0, banco.carga!!.media, 1e-9)

        val documento = MontadorLaudo.montar(
            DadosLaudo(OrigemLaudo.SIMULADO, 0, fonte = "sessão em curso", injecao = TextosInjecao.linhasDoLaudo(c.resumo()!!)),
        )
        assertTrue(documento.blocos.any { it is BlocoLaudo.Campo && "entrada de ar falsa" in it.valor })

        c.novaSessao()
        assertNull(c.resumo())
    }

    // ---------------------------------------------------------------- freeze frame

    @Test
    fun `freeze frame com campos parciais mostra o que veio e traco no resto`() {
        val stft = CatalogoTeste.padrao[Pids.STFT]!!
        val lido = ObdParser.parseFreezeFrame("42 06 00 90\r\r", stft) as RespostaObd.Ok
        assertEquals(12.5, lido.valor, 1e-9)
        assertEquals(RespostaObd.SemDados, ObdParser.parseFreezeFrame("NO DATA\r\r", CatalogoTeste.padrao[Pids.MAF]!!))

        val linhas = TextosInjecao.linhasFreezeFrame(
            FreezeFrame("P0171", mapOf(Pids.RPM to 2_350.0, Pids.STFT to lido.valor)),
            ConversorUnidades.PADRAO,
        ).toMap()
        assertEquals("2350 rpm", linhas["Rotação"])
        assertEquals("+12,5 %", linhas["STFT banco 1"])
        assertEquals("—", linhas["MAF (fluxo de ar)"])
        assertEquals("—", linhas["Tensão (ECU)"])
        assertNull("banco 2 ausente não vira linha", linhas["STFT banco 2"])
    }
}
