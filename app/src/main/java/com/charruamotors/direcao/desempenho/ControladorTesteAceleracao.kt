package com.charruamotors.direcao.desempenho

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.PrioridadePolling
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Liga o [CronometroAceleracao] ao polling: enquanto o teste está armado ou em medição, pede a
 * [PrioridadePolling] de velocidade e RPM; em qualquer outra fase — concluído, cancelado, desarmado —
 * a retira. Toda mudança de estado passa por [publicar], o único ponto que decide a prioridade, e é
 * isso que garante a volta do polling normal (e das leituras dos alertas).
 *
 * @param aplicarPrioridade entrega a prioridade ao cliente OBD (null = polling normal).
 * @param vigiados PIDs dos alertas ligados, relidos de vez em quando enquanto o teste espera a saída.
 * @param aoConcluir chamado uma vez por teste concluído.
 */
class ControladorTesteAceleracao(
    private val aplicarPrioridade: (PrioridadePolling?) -> Unit,
    private val relogioMs: () -> Long,
    private val vigiados: () -> Collection<String> = { emptyList() },
    private val aoConcluir: (ResultadoTeste) -> Unit = {},
) {
    private val cronometro = CronometroAceleracao()
    private val _estado = MutableStateFlow(cronometro.estado)
    val estado: StateFlow<EstadoTeste> = _estado.asStateFlow()

    private var aplicada: PrioridadePolling? = null

    @Synchronized
    fun armar(modalidade: ModalidadeTeste) = publicar(cronometro.armar(modalidade))

    @Synchronized
    fun cancelar(motivo: MotivoCancelamento = MotivoCancelamento.USUARIO) = publicar(cronometro.cancelar(motivo))

    /** Troca a modalidade (ou limpa o resultado) sem armar; ignorado com um teste em andamento. */
    @Synchronized
    fun selecionar(modalidade: ModalidadeTeste) {
        if (!cronometro.estado.ativo) publicar(cronometro.desarmar(modalidade))
    }

    /** Um instantâneo da telemetria: só vale a velocidade lida **neste** ciclo (valor retido não é amostra). */
    @Synchronized
    fun amostrar(dados: DadosObd) {
        if (!cronometro.estado.ativo) return
        if (dados.frescos?.contains(Pids.VELOCIDADE) == false) return
        val velocidade = dados[Pids.VELOCIDADE] ?: return
        publicar(cronometro.amostrar(velocidade, relogioMs()))
    }

    @Synchronized
    fun tique() {
        if (cronometro.estado.ativo) publicar(cronometro.tique(relogioMs()))
    }

    private fun publicar(novo: EstadoTeste) {
        val antes = _estado.value
        val desejada = when (novo.fase) {
            FaseTeste.ARMADO -> PrioridadePolling(
                PrioridadePolling.ACELERACAO,
                vigiados = vigiados().toList(),
                ciclosPorVigiado = CICLOS_POR_VIGIADO,
            )
            FaseTeste.EM_MEDICAO -> PrioridadePolling(PrioridadePolling.ACELERACAO)
            else -> null
        }
        if (desejada != aplicada) {
            aplicada = desejada
            aplicarPrioridade(desejada)
        }
        _estado.value = novo
        val resultado = novo.resultado
        if (novo.fase == FaseTeste.CONCLUIDO && antes.fase != FaseTeste.CONCLUIDO && resultado != null) aoConcluir(resultado)
    }

    private companion object {
        const val CICLOS_POR_VIGIADO = 5
    }
}
