package com.charruamotors.oficina.injecao

import com.charruamotors.obd.FreezeFrame
import com.charruamotors.obd.pid.Pids
import com.charruamotors.sistema.unidades.ConversorUnidades
import java.util.Locale

/** Textos em PT-BR do Diagnóstico de Injeção (tela e laudo). Puro (JVM). */
object TextosInjecao {

    private val localeBr: Locale = Locale.forLanguageTag("pt-BR")
    const val SEM_VALOR = "—"

    /** "+4,7 %". */
    fun trim(valor: Double?): String = valor?.let { String.format(localeBr, "%+.1f %%", it) } ?: SEM_VALOR

    /** "+4,7 % (Normal · 32 amostras)" ou quantas amostras faltam. */
    fun media(m: MediaTrim?, amostras: Int): String =
        if (m != null) {
            "${trim(m.media)} (${m.classe.titulo} · ${m.amostras} amostras)"
        } else {
            "coletando ($amostras de ${AnaliseMistura.MIN_AMOSTRAS})"
        }

    /** Linhas curtas do laudo: um campo por banco medido e um das sondas. */
    fun linhasDoLaudo(r: ResumoInjecao): List<Pair<String, String>> = buildList {
        fun banco(numero: Int, b: LeituraBanco?) {
            if (b == null) return
            fun regime(nome: String, m: MediaTrim?) = m?.let { "$nome ${trim(it.media)} (${it.classe.titulo})" }
            val medias = listOfNotNull(regime("lenta", b.lenta), regime("carga", b.carga))
            val texto = if (medias.isEmpty()) {
                "trim total ${trim(b.total)} (última leitura, sem média por regime)"
            } else {
                medias.joinToString(" · ")
            }
            add("Fuel trim banco $numero" to texto)
            if (medias.isNotEmpty()) add("Diagnóstico banco $numero" to b.dica)
        }
        banco(1, r.banco1)
        banco(2, r.banco2)
        r.sondas?.let { add("Sondas O2" to it.texto.replace("\n", " · ")) }
    }

    /**
     * Quadro congelado em linhas (rótulo → valor), nas unidades escolhidas. O que a ECU não congelou
     * aparece como "—"; o banco 2 só entra quando veio.
     */
    fun linhasFreezeFrame(ff: FreezeFrame, conversor: ConversorUnidades): List<Pair<String, String>> = buildList {
        fun linha(rotulo: String, comando: String, formatar: (Double) -> String) =
            add(rotulo to (ff[comando]?.let(formatar) ?: SEM_VALOR))
        fun numero(v: Double, casas: Int, unidade: String) = String.format(localeBr, "%.${casas}f %s", v, unidade)
        linha("Rotação", Pids.RPM) { numero(it, 0, "rpm") }
        linha("Velocidade", Pids.VELOCIDADE) { numero(conversor.velocidade(it), 0, conversor.simboloVelocidade) }
        linha("Temperatura da água", Pids.TEMPERATURA_MOTOR) { numero(conversor.temperatura(it), 0, conversor.simboloTemperatura) }
        linha("Carga do motor", Pids.CARGA_MOTOR) { numero(it, 1, "%") }
        linha("STFT banco 1", Pids.STFT, ::trim)
        linha("LTFT banco 1", Pids.LTFT, ::trim)
        if (ff[Pids.STFT_B2] != null) linha("STFT banco 2", Pids.STFT_B2, ::trim)
        if (ff[Pids.LTFT_B2] != null) linha("LTFT banco 2", Pids.LTFT_B2, ::trim)
        linha("MAP (coletor)", Pids.PRESSAO_COLETOR) { numero(it, 0, "kPa") }
        linha("MAF (fluxo de ar)", Pids.MAF) { numero(it, 2, "g/s") }
        linha("Tensão (ECU)", Pids.TENSAO_ECU) { numero(it, 2, "V") }
    }
}
