package com.charruamotors.oficina.bateria

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Veredito resumido de um componente no laudo (bateria ou alternador). */
data class VereditoResumo(val titulo: String, val gravidade: Int?) {
    val avaliado: Boolean get() = gravidade != null
}

/**
 * Textos do Teste de Bateria: vereditos da bateria (repouso + partida) e do alternador (lenta +
 * carga) e o laudo em texto simples para copiar ou compartilhar. Puro: testável na JVM.
 */
object LaudoBateria {

    private val localeBr: Locale = Locale.forLanguageTag("pt-BR")
    private val formatoDataHora: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    const val TEXTO_INCONCLUSIVO = "Inconclusivo (corte/perda de comunicação na partida — conferir nos polos)"
    const val TEXTO_QUEDA_NAO_CAPTURADA = "Inconclusivo (queda não capturada — repetir ou conferir nos polos)"

    fun rotulo(v: VereditoRepouso) = when (v) {
        VereditoRepouso.BOM -> "Bom"
        VereditoRepouso.ATENCAO -> "Atenção"
        VereditoRepouso.FRACA -> "Fraca"
    }

    fun rotulo(p: ResultadoPartida) = when (p.veredito) {
        VereditoPartida.BOM -> "Bom"
        VereditoPartida.ATENCAO -> "Atenção"
        VereditoPartida.CRITICA -> "Crítica"
        VereditoPartida.INCONCLUSIVO ->
            if (p.motivo == MotivoInconclusivo.QUEDA_NAO_CAPTURADA) TEXTO_QUEDA_NAO_CAPTURADA else TEXTO_INCONCLUSIVO
    }

    fun rotulo(v: VereditoAlternador) = when (v) {
        VereditoAlternador.SUBCARGA -> "Subcarga"
        VereditoAlternador.NORMAL -> "Normal"
        VereditoAlternador.SOBRETENSAO -> "Sobretensão / regulador"
    }

    /** Bateria: o pior entre repouso e partida. Partida inconclusiva não entra na nota. */
    fun vereditoBateria(r: ResumoTesteBateria): VereditoResumo {
        val inconclusiva = r.partida?.inconclusivo == true
        val gravidade = listOfNotNull(
            r.repouso?.veredito?.gravidade,
            r.partida?.takeIf { !it.inconclusivo }?.veredito?.gravidade,
        ).maxOrNull() ?: return VereditoResumo(if (inconclusiva) "INCONCLUSIVA (conferir nos polos)" else "Não avaliada", null)
        val titulo = when (gravidade) {
            0 -> "BOA"
            1 -> "ATENÇÃO"
            else -> "FRACA / COMPROMETIDA"
        }
        return VereditoResumo(if (inconclusiva) "$titulo (partida inconclusiva)" else titulo, gravidade)
    }

    /** Alternador: o pior entre marcha lenta e carga (no empate, a fase com carga). */
    fun vereditoAlternador(r: ResumoTesteBateria): VereditoResumo {
        val fases = listOfNotNull(r.lenta, r.carga)
        if (fases.isEmpty()) return VereditoResumo("Não avaliado", null)
        val pior = fases.reversed().maxBy { it.veredito.gravidade }.veredito
        return VereditoResumo(rotulo(pior).uppercase(localeBr), pior.gravidade)
    }

    fun linhaRepouso(r: ResultadoRepouso?): String =
        if (r == null) "não realizado" else "${v(r.tensao)} — ${rotulo(r.veredito)}"

    fun linhaPartida(p: ResultadoPartida?): String = when {
        p == null -> "não realizada"
        p.inconclusivo -> rotulo(p) + (p.vMin?.let { " · menor leitura ${v(it)}" } ?: "")
        else -> "mínimo ${p.vMin?.let(::v) ?: "—"} — ${rotulo(p)}"
    }

    fun linhaAlternador(r: ResumoTesteBateria): String {
        val partes = listOfNotNull(
            r.lenta?.let { "lenta ${v(it.media)}" },
            r.carga?.let { "com carga ${v(it.media)}" },
        )
        return if (partes.isEmpty()) "não realizado" else partes.joinToString(" · ") + " — " + vereditoAlternador(r).titulo
    }

    fun faixas(diesel: Boolean): String = listOf(
        "Referências (leitura OBD2): repouso ≥ 12,1 V bom, 11,7–12,09 V atenção, < 11,7 V fraca;",
        if (diesel) {
            "partida diesel ≥ 8,8 V bom, 7,8–8,79 V atenção, < 7,8 V crítica;"
        } else {
            "partida gasolina/flex ≥ 9,2 V bom, 8,3–9,19 V atenção, < 8,3 V crítica;"
        },
        "alternador 13,3–14,9 V normal, < 13,3 V subcarga, > 14,9 V sobretensão.",
    ).joinToString("\n")

    /** "+0,3 V". */
    fun offset(volts: Double): String = String.format(localeBr, "%+.1f V", volts)

    fun gerar(r: ResumoTesteBateria, veiculo: String?, quando: LocalDateTime): String = buildString {
        appendLine("LAUDO — TESTE DE BATERIA E ALTERNADOR")
        appendLine("Charrua OBD Pro · ${quando.format(formatoDataHora)}")
        if (!veiculo.isNullOrBlank()) appendLine("Veículo: $veiculo")
        appendLine("Motor: ${if (r.diesel) "diesel" else "gasolina/flex"} · ajuste do adaptador: ${offset(r.offsetV)}")
        appendLine()

        appendLine("BATERIA: ${vereditoBateria(r).titulo}")
        val rep = r.repouso
        append("• Repouso (motor desligado): ${linhaRepouso(rep)}")
        appendLine(if (rep == null || rep.estavel) "" else " (tensão instável: ${v(rep.minima)} a ${v(rep.maxima)})")
        if (rep?.motorPareceLigado == true) appendLine("  Atenção: acima de 13,2 V o motor parecia ligado; refaça com o motor desligado")
        val p = r.partida
        appendLine("• Partida: ${linhaPartida(p)}")
        if (p != null) {
            if (!p.inconclusivo) appendLine("  " + textoQueda(p.duracaoQuedaMs, p.motorPegou))
            if (p.lvResets > 0) {
                appendLine(
                    "  O adaptador reiniciou por subtensão (LV RESET) ${p.lvResets}× durante a partida: " +
                        "o vale real pode ter sido menor que o registrado",
                )
            }
        }
        appendLine()

        appendLine("ALTERNADOR: ${vereditoAlternador(r).titulo}")
        linhaCarga("Marcha lenta", r.lenta)
        linhaCarga("~2.000 RPM com cargas", r.carga)
        appendLine()

        appendLine(faixas(r.diesel))
        append("A leitura OBD2 costuma ficar 0,3–0,7 V abaixo dos polos; ripple estimado pela variação entre amostras.")
    }

    private fun StringBuilder.linhaCarga(nome: String, c: ResultadoCarga?) {
        if (c == null) {
            appendLine("• $nome: não realizado")
            return
        }
        appendLine("• $nome: média ${v(c.media)} · pico ${v(c.pico)} · ripple ~${v(c.ripple)} — ${rotulo(c.veredito)}")
        if (c.rippleAlto) appendLine("  Ripple alto: possível diodo da ponte retificadora ou regulador com defeito")
    }

    /** "12,52 V". */
    fun v(volts: Double): String = String.format(localeBr, "%.2f V", volts)

    /** Queda abaixo de 1 amostra (< 0,3 s) vira "Queda rápida"; senão a duração medida. */
    internal fun textoQueda(duracaoMs: Long, motorPegou: Boolean): String {
        val desfecho = if (motorPegou) "motor pegou" else "motor não pegou no tempo do teste"
        val queda = if (duracaoMs < QUEDA_RAPIDA_MS) "Queda rápida (< 0,3 s)" else "Queda de ${segundos(duracaoMs)}"
        return "$queda; $desfecho"
    }

    private const val QUEDA_RAPIDA_MS = 300L

    private fun segundos(ms: Long): String = String.format(localeBr, "%.1f s", ms / 1000.0)
}
