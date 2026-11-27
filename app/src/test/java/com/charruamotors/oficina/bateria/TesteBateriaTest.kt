package com.charruamotors.oficina.bateria

import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.EscalonadorPolling
import com.charruamotors.obd.PrioridadePolling
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TesteBateriaTest {

    // ---------------------------------------------------------------- classificação

    @Test
    fun `faixas de repouso e alternador para a leitura OBD2`() {
        assertEquals(VereditoRepouso.BOM, ClassificadorBateria.repouso(12.1))
        assertEquals(VereditoRepouso.ATENCAO, ClassificadorBateria.repouso(12.09))
        assertEquals(VereditoRepouso.ATENCAO, ClassificadorBateria.repouso(11.7))
        assertEquals(VereditoRepouso.FRACA, ClassificadorBateria.repouso(11.69))

        assertEquals(VereditoAlternador.SUBCARGA, ClassificadorBateria.alternador(13.29))
        assertEquals(VereditoAlternador.NORMAL, ClassificadorBateria.alternador(13.3))
        assertEquals(VereditoAlternador.NORMAL, ClassificadorBateria.alternador(14.9))
        assertEquals(VereditoAlternador.SOBRETENSAO, ClassificadorBateria.alternador(14.91))
    }

    @Test
    fun `partida tem faixas proprias para gasolina-flex e diesel`() {
        assertEquals(VereditoPartida.BOM, ClassificadorBateria.partida(9.2, diesel = false))
        assertEquals(VereditoPartida.ATENCAO, ClassificadorBateria.partida(9.19, diesel = false))
        assertEquals(VereditoPartida.ATENCAO, ClassificadorBateria.partida(8.3, diesel = false))
        assertEquals(VereditoPartida.CRITICA, ClassificadorBateria.partida(8.29, diesel = false))

        assertEquals(VereditoPartida.BOM, ClassificadorBateria.partida(8.8, diesel = true))
        assertEquals(VereditoPartida.ATENCAO, ClassificadorBateria.partida(8.79, diesel = true))
        assertEquals(VereditoPartida.ATENCAO, ClassificadorBateria.partida(7.8, diesel = true))
        assertEquals(VereditoPartida.CRITICA, ClassificadorBateria.partida(7.79, diesel = true))
    }

    @Test
    fun `resposta do ATRV vira leitura valida LV RESET ou perdida`() {
        assertEquals(LeituraTensao.Valida(12.4), LeituraTensao.deResposta("12.4V\r\r"))
        assertEquals(LeituraTensao.LvReset, LeituraTensao.deResposta("LV RESET\r\r"))
        assertEquals(LeituraTensao.LvReset, LeituraTensao.deResposta("\r\rELM327 v1.5\r\r"))
        assertEquals(LeituraTensao.Perdida, LeituraTensao.deResposta("", semPrompt = true))
        assertEquals(LeituraTensao.Perdida, LeituraTensao.deResposta("?\r\r"))
    }

    @Test
    fun `ripple mede a oscilacao em volta da media movel e ignora a deriva lenta`() {
        assertTrue(EstatisticaTensao.ripple(List(20) { 13.8 + it * 0.02 }) < 0.05)
        assertTrue(EstatisticaTensao.ripple(List(20) { if (it % 2 == 0) 14.0 else 14.6 }) > 0.4)
    }

    // ---------------------------------------------------------------- máquina de estados

    private var t = 0L

    /** Entrega [n] amostras iguais a cada 100 ms; para quando a captura termina. */
    private fun TesteBateria.alimentar(volts: Double?, rpm: Double?, n: Int): EventoTesteBateria? {
        repeat(n) {
            t += 100
            val leitura = volts?.let(LeituraTensao::Valida) ?: LeituraTensao.Perdida
            registrar(leitura, t, rpm)?.let { return it }
        }
        return null
    }

    private fun TesteBateria.iniciar() = iniciarCaptura(t)

    private fun concluida(etapa: EtapaBateria) = EventoTesteBateria.EtapaConcluida(etapa)

    @Test
    fun `as tres etapas com RPM chegam ao veredito`() {
        val teste = TesteBateria()
        teste.iniciar()
        // Motor ligado: não mede, pede para desligar.
        assertNull(teste.alimentar(14.0, 800.0, 40))
        assertEquals(AvisoCaptura.DESLIGUE_O_MOTOR, teste.aviso)
        assertEquals(concluida(EtapaBateria.REPOUSO), teste.alimentar(12.4, 0.0, 40))
        assertEquals(12.4, teste.resultadoRepouso!!.tensao, 1e-9)

        teste.iniciar()
        assertNull(teste.alimentar(12.4, 0.0, 10))
        assertNull(teste.alimentar(9.5, 200.0, 5))
        assertEquals(concluida(EtapaBateria.PARTIDA), teste.alimentar(14.1, 850.0, 20))
        val partida = teste.resultadoPartida!!
        assertEquals(9.5, partida.vMin!!, 1e-9)
        assertEquals(VereditoPartida.BOM, partida.veredito)
        assertTrue(partida.motorPegou)

        teste.iniciar()
        assertEquals(concluida(EtapaBateria.ALTERNADOR_LENTA), teste.alimentar(14.2, 820.0, 40))

        teste.iniciar()
        assertNull("em lenta a carga não mede", teste.alimentar(14.2, 820.0, 40))
        assertEquals(AvisoCaptura.ACELERE_A_2000, teste.aviso)
        assertNull("a janela de 3 s começa dentro da faixa", teste.alimentar(13.1, 2_000.0, 29))
        assertEquals(concluida(EtapaBateria.ALTERNADOR_CARGA), teste.alimentar(13.1, 2_000.0, 5))
        assertEquals(VereditoAlternador.SUBCARGA, teste.resultadoCarga!!.veredito)
        assertTrue(teste.concluido)
    }

    @Test
    fun `leitura abaixo de 5 V e rejeitada e o ajuste do adaptador e somado`() {
        val teste = TesteBateria().apply { offsetV = 0.5 }
        teste.pular()
        teste.iniciar()
        teste.alimentar(12.0, 0.0, 5)
        teste.alimentar(4.9, 0.0, 1) // espúria, mesmo que o ajuste a levasse a 5,4 V
        teste.alimentar(8.5, 200.0, 3)
        teste.alimentar(14.0, 800.0, 20)

        val p = teste.resultadoPartida!!
        assertEquals(9.0, p.vMin!!, 1e-9)
        assertEquals(12.5, p.tensaoInicial!!, 1e-9)
        assertEquals(VereditoPartida.ATENCAO, p.veredito)
        assertEquals(1, p.amostrasPerdidas)

        teste.diesel = true // reclassifica o vale já medido
        assertEquals(VereditoPartida.BOM, teste.resultadoPartida!!.veredito)
        assertEquals(-1.0, OffsetAdaptador.limitar(-3.0), 1e-9)
        assertEquals(1.5, OffsetAdaptador.limitar(9.0), 1e-9)
    }

    private fun partidaComFalha(falha: TesteBateria.() -> Unit): ResultadoPartida {
        val teste = TesteBateria()
        teste.pular()
        t = 0
        teste.iniciar()
        teste.alimentar(12.5, 0.0, 10)
        teste.falha()
        return teste.resultadoPartida!!
    }

    @Test
    fun `LV RESET na partida seguido do motor ligado fica inconclusivo`() {
        val p = partidaComFalha {
            alimentar(8.0, 200.0, 2)
            registrar(LeituraTensao.LvReset, t + 100)
            t += 100
            alimentar(14.0, 850.0, 20)
        }
        assertEquals(VereditoPartida.INCONCLUSIVO, p.veredito)
        assertEquals(MotivoInconclusivo.COMUNICACAO, p.motivo)
        val bateria = LaudoBateria.vereditoBateria(ResumoTesteBateria(partida = p))
        assertNull("inconclusivo não reprova a bateria", bateria.gravidade)
    }

    @Test
    fun `lacuna nas leituras durante o arranque ou conexao perdida ficam inconclusivas`() {
        val lacuna = partidaComFalha {
            alimentar(null, null, 30)
            alimentar(14.0, 850.0, 20)
        }
        assertEquals(MotivoInconclusivo.COMUNICACAO, lacuna.motivo)

        val queda = partidaComFalha {
            alimentar(7.0, null, 2)
            perderComunicacao(t + 100)
        }
        assertEquals(VereditoPartida.INCONCLUSIVO, queda.veredito)
    }

    @Test
    fun `falha antes do arranque nao contamina a partida e motor sem queda amostrada e inconclusivo`() {
        val recuperada = partidaComFalha {
            alimentar(null, null, 30)
            alimentar(12.5, 0.0, 10)
            alimentar(9.4, 200.0, 3)
            alimentar(14.0, 850.0, 20)
        }
        assertEquals(VereditoPartida.BOM, recuperada.veredito)

        val semQueda = partidaComFalha { alimentar(14.0, 850.0, 20) }
        assertEquals(MotivoInconclusivo.QUEDA_NAO_CAPTURADA, semQueda.motivo)
    }

    @Test
    fun `sem partida no prazo pede para repetir e sem RPM usa a tensao do alternador`() {
        val teste = TesteBateria()
        teste.pular()
        t = 0
        teste.iniciar()
        assertEquals(EventoTesteBateria.PartidaNaoDetectada, teste.alimentar(12.5, 0.0, 400))
        assertEquals(EtapaBateria.PARTIDA, teste.etapa)

        // Modos 21/22: só o ATRV.
        teste.comRpm = false
        teste.iniciar()
        teste.alimentar(12.5, null, 5)
        teste.alimentar(9.0, null, 5)
        assertEquals(concluida(EtapaBateria.PARTIDA), teste.alimentar(14.1, null, 20))
        assertEquals(VereditoPartida.ATENCAO, teste.resultadoPartida!!.veredito)
    }

    // ---------------------------------------------------------------- controlador e persistência

    private val aplicadas = mutableListOf<PrioridadePolling?>()
    private val concluidos = mutableListOf<TesteDaSessao>()
    private var relogio = 0L

    private fun controlador(comRpm: Boolean = true) = ControladorTesteBateria(
        aplicarPrioridade = { aplicadas += it },
        relogioMs = { relogio },
        suportados = { setOf("010C", "ATRV", "0105") },
        vigiados = { listOf("0105") },
        aoConcluir = { concluidos += it },
    ).apply { preparar(7, comRpm, diesel = false, offsetV = 0.0) }

    private fun ControladorTesteBateria.ciclos(volts: Double, rpm: Double, n: Int) = repeat(n) {
        relogio += 100
        val valores = mapOf("ATRV" to volts, "010C" to rpm)
        amostrar(DadosObd(valores, frescos = valores.keys))
    }

    @Test
    fun `controlador prioriza RPM e tensao so enquanto captura e restaura o polling`() {
        val c = controlador()
        val escalonador = EscalonadorPolling()
        escalonador.atualizarSuportados(listOf("010C", "010D", "ATRV", "0105"))

        c.iniciarCaptura()
        assertEquals(listOf("010C", "ATRV"), aplicadas.last()!!.comandos)
        escalonador.definirPrioridade(aplicadas.last())
        escalonador.proximoCiclo()
        assertEquals(listOf("010C", "ATRV"), escalonador.proximoCiclo())

        c.ciclos(12.4, 0.0, 40) // conclui o repouso
        assertNull(aplicadas.last())
        escalonador.definirPrioridade(aplicadas.last())
        assertTrue("010D" in escalonador.proximoCiclo())

        c.iniciarCaptura()
        assertTrue("a ECU pode ficar muda na partida", aplicadas.last()!!.toleraEcuMuda)
        assertTrue(aplicadas.last()!!.vigiados.isEmpty())
        c.cancelar() // cancelar ou sair da tela
        assertNull(aplicadas.last())

        c.iniciarCaptura()
        c.perderComunicacao()
        assertNull(aplicadas.last())
        assertEquals(VereditoPartida.INCONCLUSIVO, c.estado.value.resumo.partida!!.veredito)
    }

    @Test
    fun `controlador ignora valor retido e nos modos 21-22 nao pede prioridade`() {
        val c = controlador()
        c.iniciarCaptura()
        repeat(40) {
            relogio += 100
            c.amostrar(DadosObd(mapOf("ATRV" to 14.0, "010C" to 800.0), frescos = emptySet()))
        }
        assertNull(c.estado.value.tensao)

        aplicadas.clear()
        val sw4 = controlador(comRpm = false)
        sw4.iniciarCaptura()
        repeat(40) {
            relogio += 100
            sw4.registrar(LeituraTensao.Valida(12.4))
        }
        assertTrue(aplicadas.isEmpty())
        assertEquals(VereditoRepouso.BOM, sw4.estado.value.resumo.repouso!!.veredito)
    }

    @Test
    fun `teste concluido e entregue uma vez e o historico fica isolado por carro`() {
        val c = controlador()
        c.iniciarCaptura()
        c.ciclos(12.4, 0.0, 40)
        repeat(3) { c.pular() }
        assertEquals(1, concluidos.size)
        assertEquals(7L, c.resultadoDaSessao!!.veiculoId)

        val perfis = GerenciadorPerfisCarro(Memoria())
        perfis.registrarTesteBateria(7, RegistroTesteBateria.de(concluidos.single().resumo, 1_000))
        perfis.atualizar(7) { it.copy(offsetTensaoV = 0.4) }
        assertEquals(12.4, perfis.ler(7).historicoBateria.testes.single().repousoV!!, 1e-9)
        assertTrue(perfis.ler(8).historicoBateria.testes.isEmpty())
        val copia = perfis.duplicar(7, 9)
        assertEquals(0.0, copia.offsetTensaoV, 1e-9)
        assertTrue(copia.historicoBateria.testes.isEmpty())

        c.novaSessao(7)
        assertNull("o laudo só leva o teste da sessão atual", c.resultadoDaSessao)
        assertFalse(c.estado.value.resumo.temMedicao)
    }

    private class Memoria : ArmazenamentoChaveValor {
        private val mapa = mutableMapOf<String, Any>()
        override fun lerTexto(chave: String) = mapa[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean) = mapa[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            valores.forEach { (k, v) -> if (v == null) mapa.remove(k) else mapa[k] = v }
        }
    }
}
