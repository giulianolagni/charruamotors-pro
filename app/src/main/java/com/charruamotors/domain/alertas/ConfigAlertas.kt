package com.charruamotors.domain.alertas

import java.util.Locale

/** Saída de áudio do alarme (define o `AudioManager.STREAM_*` e o uso do foco de áudio). */
enum class CanalAudio(val titulo: String) {
    MIDIA("Mídia (som do carro)"),
    ALARME("Alarme"),
    NOTIFICACAO("Notificação"),
}

/** Problema numa configuração de alertas, com a mensagem para a tela. */
enum class ErroConfigAlertas(val mensagem: String) {
    AGUA("Água: a atenção deve ficar entre 60 e 140 °C e não passar do crítico"),
    ATF("Câmbio: a atenção deve ficar entre 60 e 170 °C e não passar do crítico"),
    TENSAO("Tensão: o mínimo deve ficar entre 8 e 14 V e o máximo entre 13 e 18 V, com 1 V de folga"),
}

/**
 * v2.4.0: alertas sonoros e visuais de **um** veículo (fica no perfil isolado dele, em
 * [com.charruamotors.carros.PerfilCarro]). Temperaturas em °C e tensão em V, sempre no SI.
 */
data class ConfigAlertas(
    val aguaAtiva: Boolean = true,
    val aguaAtencaoC: Double = 103.0,
    val aguaCriticoC: Double = 108.0,
    val atfAtivo: Boolean = true,
    val atfAtencaoC: Double = 110.0,
    val atfCriticoC: Double = 120.0,
    val tensaoAtiva: Boolean = true,
    /** Crítico abaixo disto com o motor ligado. */
    val tensaoMinV: Double = 11.5,
    /** Crítico acima disto com o motor ligado. */
    val tensaoMaxV: Double = 15.5,
    val dtcAtivo: Boolean = true,
    /** Aviso suave quando a ECU fica muito tempo sem responder; desligado de fábrica. */
    val comunicacaoAtiva: Boolean = false,
    val canal: CanalAudio = CanalAudio.MIDIA,
    /** Volume do bipe (0–100), relativo ao volume do canal escolhido. */
    val volume: Int = 80,
) {
    /** Primeiro limite incoerente (null = pode salvar). */
    fun validar(): ErroConfigAlertas? = when {
        !(aguaAtencaoC in 60.0..140.0 && aguaCriticoC in aguaAtencaoC..150.0) -> ErroConfigAlertas.AGUA
        !(atfAtencaoC in 60.0..170.0 && atfCriticoC in atfAtencaoC..180.0) -> ErroConfigAlertas.ATF
        !(tensaoMinV in 8.0..14.0 && tensaoMaxV in 13.0..18.0 && tensaoMaxV - tensaoMinV >= 1.0) -> ErroConfigAlertas.TENSAO
        else -> null
    }

    /** Texto `chave=valor;...` gravado numa única chave do perfil. */
    fun codificar(): String = listOf(
        "agua" to aguaAtiva, "agua_at" to aguaAtencaoC, "agua_cr" to aguaCriticoC,
        "atf" to atfAtivo, "atf_at" to atfAtencaoC, "atf_cr" to atfCriticoC,
        "tensao" to tensaoAtiva, "v_min" to tensaoMinV, "v_max" to tensaoMaxV,
        "dtc" to dtcAtivo, "com" to comunicacaoAtiva, "canal" to canal.name, "volume" to volume,
    ).joinToString(";") { (k, v) -> String.format(Locale.ROOT, "%s=%s", k, v) }

    companion object {
        /** Campos ausentes ou inválidos voltam ao padrão (nunca lança); limites incoerentes também. */
        fun decodificar(texto: String?): ConfigAlertas {
            val campos = texto.orEmpty().split(';').mapNotNull { par ->
                val i = par.indexOf('=')
                if (i <= 0) null else par.substring(0, i).trim() to par.substring(i + 1).trim()
            }.toMap()
            val p = ConfigAlertas()
            fun liga(chave: String, padrao: Boolean) = campos[chave]?.toBooleanStrictOrNull() ?: padrao
            fun numero(chave: String, padrao: Double) = campos[chave]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: padrao
            val lida = ConfigAlertas(
                aguaAtiva = liga("agua", p.aguaAtiva),
                aguaAtencaoC = numero("agua_at", p.aguaAtencaoC),
                aguaCriticoC = numero("agua_cr", p.aguaCriticoC),
                atfAtivo = liga("atf", p.atfAtivo),
                atfAtencaoC = numero("atf_at", p.atfAtencaoC),
                atfCriticoC = numero("atf_cr", p.atfCriticoC),
                tensaoAtiva = liga("tensao", p.tensaoAtiva),
                tensaoMinV = numero("v_min", p.tensaoMinV),
                tensaoMaxV = numero("v_max", p.tensaoMaxV),
                dtcAtivo = liga("dtc", p.dtcAtivo),
                comunicacaoAtiva = liga("com", p.comunicacaoAtiva),
                canal = CanalAudio.entries.firstOrNull { it.name == campos["canal"] } ?: p.canal,
                volume = campos["volume"]?.toIntOrNull()?.coerceIn(0, 100) ?: p.volume,
            )
            return when (lida.validar()) {
                null -> lida
                ErroConfigAlertas.AGUA -> decodificar(lida.copy(aguaAtencaoC = p.aguaAtencaoC, aguaCriticoC = p.aguaCriticoC).codificar())
                ErroConfigAlertas.ATF -> decodificar(lida.copy(atfAtencaoC = p.atfAtencaoC, atfCriticoC = p.atfCriticoC).codificar())
                ErroConfigAlertas.TENSAO -> decodificar(lida.copy(tensaoMinV = p.tensaoMinV, tensaoMaxV = p.tensaoMaxV).codificar())
            }
        }
    }
}
