package com.charruamotors.domain.calculo

import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids

/** Proteções do motor/câmbio/alternador que o Painel vigia (limites por veículo, v1.7). */
enum class TipoAlertaProtecao(val texto: String) {
    AGUA("ÁGUA QUENTE"),
    ATF("ATF QUENTE"),
    TENSAO("ALTERNADOR BAIXO"),
}

/** Um limite ultrapassado: [valor] lido (°C ou V) contra o [limite] configurado. */
data class AlertaProtecao(val tipo: TipoAlertaProtecao, val valor: Double, val limite: Double)

/**
 * Limites editáveis no cadastro do veículo. A tensão só vale com o motor girando a partir de
 * [rpmMinimoTensao]: com o motor desligado ou em partida a bateria fica abaixo de 13 V normalmente.
 */
data class LimitesProtecao(
    val aguaMaxC: Double = Veiculo.LIMITE_AGUA_PADRAO_C.toDouble(),
    val atfMaxC: Double = Veiculo.LIMITE_ATF_PADRAO_C.toDouble(),
    val tensaoMinV: Double = Veiculo.LIMITE_TENSAO_PADRAO_V,
    val rpmMinimoTensao: Double = RPM_MINIMO_TENSAO,
) {
    companion object {
        /** v2.2.6: `RPM ≥ 400` (antes `> 600`), o mesmo critério de motor ligado das faixas esperadas. */
        const val RPM_MINIMO_TENSAO = 400.0

        fun deVeiculo(v: Veiculo?): LimitesProtecao =
            if (v == null) LimitesProtecao() else LimitesProtecao(v.tempMaxAguaC.toDouble(), v.tempMaxAtfC.toDouble(), v.tensaoMinAlternadorV)
    }
}

/** Avalia a telemetria contra os [LimitesProtecao]. Objeto puro (JVM). */
object AlertasProtecao {

    /**
     * Alertas ativos em [dados] (°C e V do catálogo). O ATF pode vir do câmbio (`7E1`) ou do
     * espelho no motor (`7E0`); leitura ausente nunca dispara alerta. O limite é inclusivo na
     * temperatura (≥) e exclusivo na tensão (< mínimo).
     */
    fun avaliar(dados: ValoresSensores?, limites: LimitesProtecao): List<AlertaProtecao> {
        if (dados == null) return emptyList()
        return buildList {
            dados[Pids.TEMPERATURA_MOTOR]?.takeIf { it.isFinite() && it >= limites.aguaMaxC }
                ?.let { add(AlertaProtecao(TipoAlertaProtecao.AGUA, it, limites.aguaMaxC)) }
            Pids.COMANDOS_ATF.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() } }
                ?.takeIf { it >= limites.atfMaxC }
                ?.let { add(AlertaProtecao(TipoAlertaProtecao.ATF, it, limites.atfMaxC)) }
            val rpm = dados[Pids.RPM]
            val tensao = dados[Pids.TENSAO_BATERIA]
            if (rpm != null && rpm.isFinite() && rpm >= limites.rpmMinimoTensao && tensao != null && tensao.isFinite() && tensao < limites.tensaoMinV) {
                add(AlertaProtecao(TipoAlertaProtecao.TENSAO, tensao, limites.tensaoMinV))
            }
        }
    }
}

/**
 * Faixas esperadas do veículo ativo, para o destaque (sem som) dos mostradores. Vive no Application.
 * v2.4.0: o som e os alertas passaram ao [com.charruamotors.domain.alertas.MonitorAlertasCriticos].
 */
class CentralAlertas {

    /** Faixas mínimas/máximas esperadas do veículo ativo (v2.1): destacam o mostrador fora da faixa. */
    @Volatile
    var faixas: FaixasVeiculo = FaixasVeiculo.genericas(null)

    /** O mostrador com estes [comandos] está fora da faixa esperada em [dados]? */
    fun foraDaFaixa(comandos: Collection<String>, dados: ValoresSensores?): Boolean = faixas.foraDaFaixa(comandos, dados)
}
