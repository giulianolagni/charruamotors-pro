package com.charruamotors.domain.alertas

import com.charruamotors.obd.pid.Pids
import java.util.Locale

/** Regras vigiadas em tempo real. */
enum class RegraAlerta(val titulo: String) {
    TEMP_AGUA("ÁGUA QUENTE"),
    TEMP_ATF("CÂMBIO QUENTE"),
    TENSAO("TENSÃO / ALTERNADOR"),
    DTC("FALHA NOVA / MIL"),
    COMUNICACAO("SEM COMUNICAÇÃO"),
}

enum class NivelAlerta(val titulo: String) { ATENCAO("ATENÇÃO"), CRITICO("CRÍTICO") }

/** Um alerta em vigor, com o valor atual já formatado para a tela. */
data class AlertaAtivo(
    val regra: RegraAlerta,
    val nivel: NivelAlerta,
    /** `110 °C`, `11,2 V`, `MIL acesa · 2 DTC`. */
    val valor: String,
    /** O motorista calou o bipe deste alerta: o destaque visual continua. */
    val silenciado: Boolean = false,
) {
    /** `CRÍTICO · ÁGUA QUENTE 110 °C`. */
    val texto: String get() = "${nivel.titulo} · ${regra.titulo} $valor"
}

/** O que está em alerta agora, do mais grave para o menos grave. */
data class EstadoAlertas(val ativos: List<AlertaAtivo> = emptyList()) {
    val principal: AlertaAtivo? get() = ativos.firstOrNull()

    /** Há algum bipe que o botão "Silenciar" ainda calaria? */
    val podeSilenciar: Boolean get() = ativos.any { !it.silenciado }

    /** `CRÍTICO · ÁGUA QUENTE 110 °C` (uma linha por alerta). */
    val texto: String get() = ativos.joinToString("\n") { it.texto }

    fun de(regra: RegraAlerta): AlertaAtivo? = ativos.firstOrNull { it.regra == regra }
}

/**
 * Resultado de uma avaliação: o [estado] atual, o bipe a tocar agora ([som], o mais grave entre os
 * devidos) e os alertas que acabaram de [entradas] em vigor ou subir de nível (para o log).
 */
data class AvaliacaoAlertas(val estado: EstadoAlertas, val som: NivelAlerta? = null, val entradas: List<AlertaAtivo> = emptyList())

/**
 * v2.4.0: motor de regras dos alertas críticos. Puro (JVM), com o relógio passado em cada chamada.
 *
 * - **Debounce:** só entra em alerta com [LEITURAS_PARA_DISPARAR] leituras **frescas** seguidas na
 *   condição, ou com ao menos 2 leituras frescas na condição cobrindo [TEMPO_PARA_DISPARAR_MS]. Um
 *   valor retido da fila lenta não é leitura nova, e um único byte corrompido nunca dispara.
 * - **Valores impossíveis** (ex.: água em 215 °C, o `FF`) são ignorados.
 * - **Histerese:** o alerta só se encerra (e rearma) com [LEITURAS_PARA_ENCERRAR] leituras seguidas
 *   na faixa segura: abaixo do limite de atenção nas temperaturas; [FOLGA_TENSAO_V] para dentro dos
 *   limites na tensão. Enquanto isso, o nível mais alto atingido é mantido.
 * - **Tensão:** só com `RPM > 400`.
 * - **Silenciar:** cala por [DURACAO_SILENCIO_MS] os alertas em vigor; uma regra nova ou uma subida
 *   de nível voltam a tocar.
 */
class MonitorAlertasCriticos(config: ConfigAlertas = ConfigAlertas()) {

    /** Situação de uma regra de limite (temperaturas e tensão). */
    private class Trilho {
        var ativo: NivelAlerta? = null
        val atencao = Sequencia()
        val critico = Sequencia()
        var seguras = 0
        var valor = 0.0
        var ultimaMs = 0L
    }

    /** Leituras frescas consecutivas dentro de uma condição. */
    private class Sequencia {
        private var quantidade = 0
        private var inicioMs = 0L
        val pendente: Boolean get() = quantidade > 0

        fun registrar(naCondicao: Boolean, agoraMs: Long): Boolean {
            if (!naCondicao) {
                zerar()
                return false
            }
            if (quantidade == 0) inicioMs = agoraMs
            quantidade++
            return quantidade >= LEITURAS_PARA_DISPARAR || (quantidade >= 2 && agoraMs - inicioMs >= TEMPO_PARA_DISPARAR_MS)
        }

        fun zerar() {
            quantidade = 0
        }
    }

    private class Silencio(val nivel: NivelAlerta, val ateMs: Long)

    private val trilhos = mapOf(RegraAlerta.TEMP_AGUA to Trilho(), RegraAlerta.TEMP_ATF to Trilho(), RegraAlerta.TENSAO to Trilho())
    private val silencios = mutableMapOf<RegraAlerta, Silencio>()
    private val ultimoSomMs = mutableMapOf<RegraAlerta, Long>()

    // DTC / MIL
    private var dtcBase: Int? = null
    private val sequenciaDtc = Sequencia()
    private var dtcAtivoDesdeMs: Long? = null
    private var textoDtc = ""
    private var milAnterior = false
    private var quedasDtc = 0

    // comunicação
    private var ultimaEcuMs: Long? = null
    private var semComunicacao = false
    private var segundosSemEcu = 0L

    var config: ConfigAlertas = config
        @Synchronized get
        @Synchronized set(nova) {
            field = nova
            if (!nova.aguaAtiva) encerrar(RegraAlerta.TEMP_AGUA)
            if (!nova.atfAtivo) encerrar(RegraAlerta.TEMP_ATF)
            if (!nova.tensaoAtiva) encerrar(RegraAlerta.TENSAO)
            if (!nova.dtcAtivo) encerrarDtc()
            if (!nova.comunicacaoAtiva) semComunicacao = false
        }

    /**
     * PIDs com um disparo em confirmação (água e tensão): o polling pode lê-los mais depressa até a
     * regra decidir.
     */
    val chavesEmConfirmacao: Set<String>
        @Synchronized get() = buildSet {
            fun confirmando(r: RegraAlerta): Boolean {
                val t = trilhos.getValue(r)
                return (t.ativo == null && t.atencao.pendente) || (t.ativo != NivelAlerta.CRITICO && t.critico.pendente)
            }
            if (confirmando(RegraAlerta.TEMP_AGUA)) add(Pids.TEMPERATURA_MOTOR)
            if (confirmando(RegraAlerta.TENSAO)) add(Pids.TENSAO_BATERIA)
        }

    /** Há uma leitura de MIL/DTC aguardando confirmação? */
    val dtcEmConfirmacao: Boolean @Synchronized get() = sequenciaDtc.pendente

    /**
     * Um instantâneo da telemetria. [frescos] são as chaves lidas **neste ciclo** (null = todas);
     * as demais são valores retidos e não contam para o debounce.
     */
    @Synchronized
    fun processar(valores: Map<String, Double>, frescos: Set<String>?, agoraMs: Long): AvaliacaoAlertas {
        val entradas = mutableListOf<RegraAlerta>()
        fun fresco(chave: String): Double? =
            if (frescos == null || chave in frescos) valores[chave]?.takeIf { it.isFinite() } else null

        if (valores.keys.any { it != Pids.TENSAO_BATERIA && (frescos == null || it in frescos) && it.startsWithEcu() }) {
            ultimaEcuMs = agoraMs
            semComunicacao = false
        }

        if (config.aguaAtiva) {
            fresco(Pids.TEMPERATURA_MOTOR)?.takeIf { it in FAIXA_AGUA_VALIDA }?.let { v ->
                temperatura(RegraAlerta.TEMP_AGUA, v, config.aguaAtencaoC, config.aguaCriticoC, agoraMs, entradas)
            }
        }
        if (config.atfAtivo) {
            Pids.COMANDOS_ATF.firstNotNullOfOrNull { c -> fresco(c)?.takeIf { it in FAIXA_ATF_VALIDA } }?.let { v ->
                temperatura(RegraAlerta.TEMP_ATF, v, config.atfAtencaoC, config.atfCriticoC, agoraMs, entradas)
            }
        }
        if (config.tensaoAtiva) {
            val rpm = valores[Pids.RPM]?.takeIf { it.isFinite() }
            if (rpm == null || rpm <= RPM_MOTOR_LIGADO) {
                // Motor desligado ou em partida: a bateria baixa é normal.
                encerrar(RegraAlerta.TENSAO)
            } else {
                fresco(Pids.TENSAO_BATERIA)?.takeIf { it in FAIXA_TENSAO_VALIDA }?.let { v -> tensao(v, agoraMs, entradas) }
            }
        }
        return concluir(agoraMs, entradas)
    }

    /**
     * Leitura do `0101` (MIL e quantidade de DTCs). A primeira com a luz apagada vira a referência;
     * luz acesa, ou mais códigos que a referência, disparam depois de confirmadas.
     */
    @Synchronized
    fun observarMil(milAcesa: Boolean, quantidade: Int, agoraMs: Long): AvaliacaoAlertas {
        val entradas = mutableListOf<RegraAlerta>()
        if (config.dtcAtivo) {
            val base = dtcBase
            val novo = (base != null && (quantidade > base || (milAcesa && !milAnterior))) || (base == null && milAcesa)
            val caiu = base != null && (quantidade < base || (!milAcesa && milAnterior))
            quedasDtc = if (caiu) quedasDtc + 1 else 0
            if (base == null && !milAcesa) {
                dtcBase = quantidade
                sequenciaDtc.zerar()
            } else if (sequenciaDtc.registrar(novo, agoraMs)) {
                textoDtc = (if (milAcesa) "MIL acesa" else "código novo") + " · $quantidade DTC"
                dtcBase = quantidade
                milAnterior = milAcesa
                dtcAtivoDesdeMs = agoraMs
                sequenciaDtc.zerar()
                silencios.remove(RegraAlerta.DTC)
                ultimoSomMs.remove(RegraAlerta.DTC)
                entradas += RegraAlerta.DTC
            } else if (quedasDtc >= LEITURAS_PARA_ENCERRAR) {
                // Códigos apagados (confirmado): a referência desce e a luz rearma.
                dtcBase = quantidade
                milAnterior = milAcesa
                if (!milAcesa && quantidade == 0) encerrarDtc(manterBase = true)
            }
        }
        return concluir(agoraMs, entradas)
    }

    /** Passagem do tempo sem leitura nova: repetição do bipe, fim do silêncio, comunicação perdida. */
    @Synchronized
    fun avaliar(agoraMs: Long): AvaliacaoAlertas {
        val entradas = mutableListOf<RegraAlerta>()
        val ultima = ultimaEcuMs
        if (config.comunicacaoAtiva && ultima != null && agoraMs - ultima >= TEMPO_SEM_COMUNICACAO_MS) {
            segundosSemEcu = (agoraMs - ultima) / 1000
            if (!semComunicacao) {
                semComunicacao = true
                ultimoSomMs.remove(RegraAlerta.COMUNICACAO)
                entradas += RegraAlerta.COMUNICACAO
            }
        }
        return concluir(agoraMs, entradas)
    }

    /** Polling parado de propósito (sessão manual das ferramentas): não é perda de comunicação. */
    @Synchronized
    fun pausarComunicacao(agoraMs: Long) {
        if (ultimaEcuMs != null) ultimaEcuMs = agoraMs
        semComunicacao = false
    }

    /** Cala por 5 minutos o bipe dos alertas em vigor. */
    @Synchronized
    fun silenciar(agoraMs: Long): EstadoAlertas {
        estado(agoraMs).ativos.forEach { silencios[it.regra] = Silencio(it.nivel, agoraMs + DURACAO_SILENCIO_MS) }
        return estado(agoraMs)
    }

    /** Fim da sessão: esquece alertas, referências e silêncios. */
    @Synchronized
    fun reiniciar() {
        trilhos.keys.forEach(::encerrar)
        encerrarDtc()
        silencios.clear()
        ultimoSomMs.clear()
        ultimaEcuMs = null
        semComunicacao = false
    }

    // ------------------------------------------------------------------ regras

    private fun temperatura(regra: RegraAlerta, v: Double, atencao: Double, critico: Double, agoraMs: Long, entradas: MutableList<RegraAlerta>) =
        avancar(regra, v, naAtencao = v >= atencao, noCritico = v >= critico, seguro = v < atencao, NivelAlerta.ATENCAO, agoraMs, entradas)

    private fun tensao(v: Double, agoraMs: Long, entradas: MutableList<RegraAlerta>) {
        val fora = v < config.tensaoMinV || v > config.tensaoMaxV
        val seguro = v >= config.tensaoMinV + FOLGA_TENSAO_V && v <= config.tensaoMaxV - FOLGA_TENSAO_V
        // A tensão não tem faixa de atenção: fora dos limites é crítico.
        avancar(RegraAlerta.TENSAO, v, naAtencao = false, noCritico = fora, seguro, null, agoraMs, entradas)
    }

    private fun avancar(
        regra: RegraAlerta,
        v: Double,
        naAtencao: Boolean,
        noCritico: Boolean,
        seguro: Boolean,
        nivelAtencao: NivelAlerta?,
        agoraMs: Long,
        entradas: MutableList<RegraAlerta>,
    ) {
        val t = trilhos.getValue(regra)
        // Intervalo grande sem leitura: as anteriores não são mais "seguidas".
        if (agoraMs - t.ultimaMs > VALIDADE_SEQUENCIA_MS) {
            t.atencao.zerar()
            t.critico.zerar()
            t.seguras = 0
        }
        t.ultimaMs = agoraMs
        t.valor = v
        val confirmouCritico = t.critico.registrar(noCritico, agoraMs)
        val confirmouAtencao = nivelAtencao != null && t.atencao.registrar(naAtencao, agoraMs)
        val novo = when {
            confirmouCritico -> NivelAlerta.CRITICO
            confirmouAtencao -> nivelAtencao
            else -> null
        }
        val atual = t.ativo
        if (novo != null && (atual == null || novo > atual)) {
            t.ativo = novo
            silencios[regra]?.let { if (novo > it.nivel) silencios.remove(regra) }
            ultimoSomMs.remove(regra)
            entradas += regra
        }
        t.seguras = if (seguro) t.seguras + 1 else 0
        if (t.ativo != null && t.seguras >= LEITURAS_PARA_ENCERRAR) t.ativo = null
    }

    private fun encerrar(regra: RegraAlerta) {
        val t = trilhos.getValue(regra)
        t.ativo = null
        t.atencao.zerar()
        t.critico.zerar()
        t.seguras = 0
    }

    private fun encerrarDtc(manterBase: Boolean = false) {
        dtcAtivoDesdeMs = null
        sequenciaDtc.zerar()
        quedasDtc = 0
        if (!manterBase) {
            dtcBase = null
            milAnterior = false
        }
    }

    // ------------------------------------------------------------------ saída

    private fun estado(agoraMs: Long): EstadoAlertas {
        fun calado(r: RegraAlerta, n: NivelAlerta) = silencios[r]?.let { agoraMs < it.ateMs && n <= it.nivel } == true
        fun alerta(r: RegraAlerta, n: NivelAlerta, valor: String) = AlertaAtivo(r, n, valor, calado(r, n))
        val lista = buildList {
            for ((regra, t) in trilhos) {
                val nivel = t.ativo ?: continue
                val valor = if (regra == RegraAlerta.TENSAO) {
                    String.format(Locale.ROOT, "%.1f V", t.valor).replace('.', ',')
                } else {
                    String.format(Locale.ROOT, "%.0f °C", t.valor)
                }
                add(alerta(regra, nivel, valor))
            }
            val desde = dtcAtivoDesdeMs
            if (desde != null && agoraMs - desde < DURACAO_AVISO_DTC_MS) add(alerta(RegraAlerta.DTC, NivelAlerta.ATENCAO, textoDtc))
            if (semComunicacao) add(alerta(RegraAlerta.COMUNICACAO, NivelAlerta.ATENCAO, "há $segundosSemEcu s"))
        }
        return EstadoAlertas(lista.sortedWith(compareByDescending<AlertaAtivo> { it.nivel }.thenBy { it.regra }))
    }

    private fun concluir(agoraMs: Long, entradas: List<RegraAlerta>): AvaliacaoAlertas {
        val atual = estado(agoraMs)
        var som: NivelAlerta? = null
        for (a in atual.ativos) {
            if (a.silenciado) continue
            val ultimo = ultimoSomMs[a.regra]
            // DTC e comunicação avisam uma vez; os limites repetem enquanto durarem.
            val repete = a.regra != RegraAlerta.DTC && a.regra != RegraAlerta.COMUNICACAO
            val intervalo = if (a.nivel == NivelAlerta.CRITICO) REPETICAO_CRITICO_MS else REPETICAO_ATENCAO_MS
            if (ultimo != null && (!repete || agoraMs - ultimo < intervalo)) continue
            ultimoSomMs[a.regra] = agoraMs
            som = maxOf(som ?: a.nivel, a.nivel)
        }
        return AvaliacaoAlertas(atual, som, entradas.mapNotNull(atual::de))
    }

    /** Chave que veio da ECU (PID OBD2 ou de montadora), e não do celular ou calculada pelo app. */
    private fun String.startsWithEcu(): Boolean = this != Pids.VELOCIDADE_GPS && this != Pids.RELACAO_AF_REAL

    companion object {
        const val LEITURAS_PARA_DISPARAR = 3
        const val TEMPO_PARA_DISPARAR_MS = 3_000L
        const val LEITURAS_PARA_ENCERRAR = 2
        const val DURACAO_SILENCIO_MS = 5 * 60_000L
        const val REPETICAO_CRITICO_MS = 10_000L
        const val REPETICAO_ATENCAO_MS = 60_000L
        const val DURACAO_AVISO_DTC_MS = 60_000L
        const val TEMPO_SEM_COMUNICACAO_MS = 15_000L

        /** Leituras separadas por mais que isto não formam uma sequência (a fila lenta leva ~30 s por volta). */
        const val VALIDADE_SEQUENCIA_MS = 120_000L
        const val RPM_MOTOR_LIGADO = 400.0
        const val FOLGA_TENSAO_V = 0.3

        private val FAIXA_AGUA_VALIDA = -40.0..200.0
        private val FAIXA_ATF_VALIDA = -40.0..200.0
        private val FAIXA_TENSAO_VALIDA = 5.0..25.0
    }
}
