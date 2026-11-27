package com.charruamotors.direcao.desempenho

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.PrioridadePolling
import com.charruamotors.obd.pid.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v2.6.0: máquina de estados dos testes de aceleração e a prioridade de polling que ela liga. */
class CronometroAceleracaoTest {

    private val cronometro = CronometroAceleracao()

    /** Alimenta pares (instante ms, km/h) e devolve o último estado. */
    private fun amostras(vararg pares: Pair<Long, Int>): EstadoTeste {
        pares.forEach { (t, v) -> cronometro.amostrar(v.toDouble(), t) }
        return cronometro.estado
    }

    @Test
    fun `0-100 passa por todas as fases e interpola a chegada`() {
        assertEquals(FaseTeste.AGUARDANDO_PARADA, cronometro.armar(ModalidadeTeste.V0_100).fase)
        assertEquals(FaseTeste.AGUARDANDO_PARADA, amostras(0L to 30).fase)
        assertEquals(FaseTeste.ARMADO, amostras(500L to 0).fase)
        assertEquals(FaseTeste.EM_MEDICAO, amostras(1_000L to 4).fase)
        val fim = amostras(3_000L to 35, 5_000L to 60, 7_000L to 80, 9_000L to 96, 9_400L to 104)

        assertEquals(FaseTeste.CONCLUIDO, fim.fase)
        // 100 km/h fica no meio de 96 (9000 ms) e 104 (9400 ms): 9200 − 1000.
        assertEquals(ResultadoTeste(ModalidadeTeste.V0_100, 8_200L, 100.0), fim.resultado)
        // Amostras depois da chegada não mexem no resultado.
        assertEquals(fim, amostras(10_000L to 130))
    }

    @Test
    fun `o pico de 255 e ignorado ao disparar, medir e interpolar`() {
        cronometro.armar(ModalidadeTeste.V0_100)
        amostras(0L to 0)
        assertEquals(FaseTeste.ARMADO, amostras(200L to 255).fase)
        amostras(400L to 5, 2_000L to 50)
        assertEquals(FaseTeste.EM_MEDICAO, amostras(2_200L to 255).fase)
        val fim = amostras(4_000L to 90, 4_400L to 110)

        // 100 entre 90 (4000) e 110 (4400): 4200 − 400. O 255 não virou referência.
        assertEquals(3_800L, fim.resultado!!.tempoMs)
    }

    @Test
    fun `retomada interpola a saida e a chegada sem precisar parar`() {
        cronometro.armar(ModalidadeTeste.V80_120)
        assertEquals(FaseTeste.ARMADO, amostras(0L to 70).fase)
        assertEquals(FaseTeste.EM_MEDICAO, amostras(1_000L to 90).fase)
        val fim = amostras(2_000L to 110, 3_000L to 130)

        // Saiu em 500 ms (80 km/h) e chegou em 2500 ms (120 km/h).
        assertEquals(2_000L, fim.resultado!!.tempoMs)
    }

    @Test
    fun `200 m por integracao da velocidade com a chegada interpolada`() {
        cronometro.armar(ModalidadeTeste.D200)
        // 72 km/h = 20 m/s constantes a partir da saída: 200 m em 10 s.
        amostras(0L to 0, 1_000L to 72)
        var estado = cronometro.estado
        var t = 1_000L
        while (estado.fase == FaseTeste.EM_MEDICAO) {
            t += 1_500
            estado = amostras(t to 72)
        }

        assertEquals(FaseTeste.CONCLUIDO, estado.fase)
        assertEquals(10_000L, estado.resultado!!.tempoMs)
        assertEquals(200.0, estado.distanciaM, 1e-6)
    }

    @Test
    fun `cancela ao desacelerar ou sem leituras e rearma se parar antes da chegada`() {
        cronometro.armar(ModalidadeTeste.V0_100)
        amostras(0L to 0, 300L to 10, 1_000L to 40)
        assertEquals(MotivoCancelamento.DESACELEROU, amostras(1_300L to 30).motivo)

        cronometro.armar(ModalidadeTeste.V0_100)
        amostras(2_000L to 0, 2_300L to 3)
        assertEquals(FaseTeste.ARMADO, amostras(2_600L to 0).fase)

        amostras(2_900L to 8)
        assertEquals(MotivoCancelamento.SEM_LEITURAS, cronometro.tique(2_900L + CronometroAceleracao.MAX_LACUNA_MS + 1).motivo)

        // Primeira leitura em movimento já além da chegada: não há o que medir.
        cronometro.armar(ModalidadeTeste.V0_60)
        assertEquals(FaseTeste.AGUARDANDO_PARADA, amostras(10_000L to 0, 10_300L to 70).fase)
    }

    // ---------------------------------------------------------------- controlador e polling

    private var agora = 0L
    private val aplicadas = mutableListOf<PrioridadePolling?>()
    private val concluidos = mutableListOf<ResultadoTeste>()
    private val controlador = ControladorTesteAceleracao(
        aplicarPrioridade = { aplicadas += it },
        relogioMs = { agora },
        vigiados = { listOf(Pids.TEMPERATURA_MOTOR) },
        aoConcluir = { concluidos += it },
    )

    private fun ler(t: Long, v: Int, fresca: Boolean = true) {
        agora = t
        controlador.amostrar(DadosObd(mapOf(Pids.VELOCIDADE to v.toDouble()), frescos = if (fresca) setOf(Pids.VELOCIDADE) else emptySet()))
    }

    @Test
    fun `a prioridade liga ao armar, tira os vigiados na medicao e sai ao concluir`() {
        controlador.armar(ModalidadeTeste.V0_60)
        assertEquals(emptyList<PrioridadePolling?>(), aplicadas)
        ler(0, 0)
        ler(300, 0)
        assertEquals(listOf(Pids.TEMPERATURA_MOTOR), aplicadas.single()!!.vigiados)
        ler(600, 20)
        assertEquals(PrioridadePolling(PrioridadePolling.ACELERACAO), aplicadas.last())
        ler(900, 65, fresca = false) // valor retido: não é amostra
        assertEquals(FaseTeste.EM_MEDICAO, controlador.estado.value.fase)
        ler(1_200, 70)

        assertNull(aplicadas.last())
        assertEquals(3, aplicadas.size)
        assertEquals(1, concluidos.size)
    }

    @Test
    fun `a prioridade sai em qualquer cancelamento`() {
        for (motivo in listOf(MotivoCancelamento.USUARIO, MotivoCancelamento.SAIU_DA_TELA, MotivoCancelamento.DESCONECTADO)) {
            controlador.armar(ModalidadeTeste.V0_100)
            ler(agora + 100, 0)
            controlador.cancelar(motivo)
            assertNull("$motivo", aplicadas.last())
        }
        // Armado e esquecido: o tique devolve o polling normal.
        controlador.armar(ModalidadeTeste.V0_100)
        ler(agora + 100, 0)
        agora += CronometroAceleracao.MAX_ARMADO_MS + 1
        controlador.tique()
        assertNull(aplicadas.last())
        assertEquals(MotivoCancelamento.ARMADO_DEMAIS, controlador.estado.value.motivo)
        // Rearmar por cima de um teste armado também passa pelo polling normal.
        controlador.armar(ModalidadeTeste.V0_100)
        ler(agora + 100, 0)
        controlador.armar(ModalidadeTeste.V0_60)
        assertNull(aplicadas.last())
    }
}
