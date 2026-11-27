package com.charruamotors.oficina.sensores

import com.charruamotors.obd.pid.Pids

/** Conjunto de sensores dos Gráficos em Tempo Real aplicado com um toque (v2.5.0). */
data class PresetGrafico(val nome: String, val comandos: List<String>)

/**
 * Presets dos gráficos: os prontos (montados com o que o carro da sessão suporta) e os salvos pelo
 * usuário no perfil de cada veículo. Puro (JVM).
 */
object PresetsGrafico {

    const val MAX_SALVOS = 8
    const val MAX_NOME = 24

    const val TERMICO = "Térmico"
    const val TURBO_INJECAO = "Turbo/Injeção"

    private const val ENTRE_PRESETS = ";"
    private const val ENTRE_NOME_E_SENSORES = "|"
    private const val ENTRE_SENSORES = ","

    /** Água + ATF + Carga: cada posição aceita os equivalentes, na ordem de preferência. */
    private val POSICOES_TERMICO = listOf(
        listOf(Pids.TEMPERATURA_MOTOR),
        Pids.COMANDOS_ATF,
        listOf(Pids.CARGA_MOTOR),
    )

    private val PEDAL_OU_BORBOLETA = listOf(Pids.BORBOLETA, Pids.PEDAL_ACELERADOR, "TOYOTA_PEDAL_D")

    /**
     * Os prontos que este carro consegue mostrar.
     *
     * @param suportados comandos da sessão.
     * @param responderam os que já deram leitura: preferidos entre os equivalentes.
     * @param diesel null = desconhecido: decide pela presença do rail. No diesel a 4ª posição é o
     *   rail; nos demais, o STFT.
     */
    fun prontos(suportados: Collection<String>, responderam: Set<String>, diesel: Boolean?): List<PresetGrafico> {
        val ehDiesel = diesel ?: (Pids.PRESSAO_RAIL in suportados)
        val mistura = if (ehDiesel) listOf(Pids.PRESSAO_RAIL, Pids.STFT) else listOf(Pids.STFT, Pids.PRESSAO_RAIL)
        val turbo = listOf(listOf(Pids.RPM), listOf(Pids.PRESSAO_COLETOR), PEDAL_OU_BORBOLETA, mistura)
        return listOf(TERMICO to POSICOES_TERMICO, TURBO_INJECAO to turbo).mapNotNull { (nome, posicoes) ->
            val comandos = posicoes.mapNotNull { opcoes ->
                opcoes.firstOrNull { it in suportados && it in responderam } ?: opcoes.firstOrNull { it in suportados }
            }
            if (comandos.isEmpty()) null else PresetGrafico(nome, comandos.take(SelecaoGrafico.MAXIMO))
        }
    }

    /** Sem os separadores da gravação e no tamanho do chip; vazio = nome inválido. */
    fun normalizarNome(nome: String): String =
        nome.replace(Regex("[;|\\r\\n]"), " ").trim().take(MAX_NOME).trim()

    /**
     * [salvos] com [novo]: um nome repetido (sem diferenciar maiúsculas) substitui o antigo no mesmo
     * lugar. Null = recusado (nome ou sensores vazios, ou já há [MAX_SALVOS]).
     */
    fun salvar(salvos: List<PresetGrafico>, novo: PresetGrafico): List<PresetGrafico>? {
        val nome = normalizarNome(novo.nome)
        val comandos = novo.comandos.distinct().take(SelecaoGrafico.MAXIMO)
        if (nome.isEmpty() || comandos.isEmpty()) return null
        val preset = PresetGrafico(nome, comandos)
        val posicao = salvos.indexOfFirst { it.nome.equals(nome, ignoreCase = true) }
        return when {
            posicao >= 0 -> salvos.toMutableList().also { it[posicao] = preset }
            salvos.size >= MAX_SALVOS -> null
            else -> salvos + preset
        }
    }

    /** `nome|010C,010B;outro|0105`; null sem presets (a chave sai do perfil). */
    fun codificar(presets: List<PresetGrafico>): String? =
        presets.takeIf { it.isNotEmpty() }?.joinToString(ENTRE_PRESETS) {
            normalizarNome(it.nome) + ENTRE_NOME_E_SENSORES + it.comandos.joinToString(ENTRE_SENSORES)
        }

    /** Entradas ilegíveis são ignoradas (nunca lança). */
    fun decodificar(texto: String?): List<PresetGrafico> =
        texto.orEmpty().split(ENTRE_PRESETS).fold(emptyList()) { lidos, item ->
            val nome = item.substringBefore(ENTRE_NOME_E_SENSORES, "")
            val comandos = item.substringAfter(ENTRE_NOME_E_SENSORES, "").split(ENTRE_SENSORES).map { it.trim() }.filter { it.isNotEmpty() }
            salvar(lidos, PresetGrafico(nome, comandos)) ?: lidos
        }
}
