package com.charruamotors.obd.pid

import com.charruamotors.obd.ObdParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.zip.CRC32

/**
 * PID extra de um veículo, importado (CSV do Torque Pro ou JSON do app) ou cadastrado à mão.
 * Só existe validado ([criar]): serviços de **leitura** (`01`, `21`, `22` — nunca limpeza,
 * atuador, rotina ou sessão), cabeçalho CAN de 11 bits ou automático e fórmula com `A`–`D`.
 */
class PidCustomizado private constructor(
    val nome: String,
    val nomeCurto: String,
    /** Serviço + identificador (`010C`, `2182`, `22F40C`). */
    val modoPid: String,
    val equacao: String,
    val min: Double,
    val max: Double,
    val unidade: String,
    /** Cabeçalho CAN de 11 bits (`7E0`); null = o padrão do protocolo. */
    val cabecalho: String?,
) {
    private val formula = Formula.compilar(equacao)

    /** Identidade estável (mesmo pedido, fórmula e nome curto = o mesmo PID): é a coluna nos logs. */
    val chave: String = "CUSTOM_" + "%08X".format(
        CRC32().apply { update("${cabecalho.orEmpty()}|$modoPid|$equacao|$nomeCurto".toByteArray()) }.value,
    )

    /**
     * Definição para o polling no [protocolo] ativo (`ATDPN`). O cabeçalho próprio só vale em CAN de
     * 11 bits (6 e 8): na linha K (3–5), no J1850, no CAN de 29 bits ou sem protocolo legível ele é
     * ignorado e o pedido sai com o cabeçalho padrão, sem nenhum `ATSH`.
     */
    fun definicao(protocolo: Int?): DefinicaoPid = DefinicaoPid(
        comando = chave,
        nome = nome,
        unidade = unidade,
        bytes = formula.bytesNecessarios,
        formula = formula,
        min = min,
        max = max,
        casasDecimais = 2,
        estendida = RequisicaoEstendida(
            cabecalho = cabecalho?.takeIf { protocolo in ObdParser.PROTOCOLOS_CAN_11_BITS }.orEmpty(),
            servico = modoPid.take(2),
            identificador = modoPid.drop(2),
            customizada = true,
        ),
    )

    override fun equals(other: Any?): Boolean = other is PidCustomizado && campos() == other.campos()

    override fun hashCode(): Int = campos().hashCode()

    override fun toString() = "$nomeCurto $modoPid"

    private fun campos() = listOf(nome, nomeCurto, modoPid, equacao, min, max, unidade, cabecalho)

    companion object {
        /** Cada PID é uma requisição a mais na fila lenta: o teto mantém o Painel ágil. */
        const val MAXIMO_POR_VEICULO = 40

        /** Limites sem `Min`/`Max` informados: não descartam leitura nenhuma. */
        const val MIN_PADRAO = -1_000_000.0
        const val MAX_PADRAO = 1_000_000.0

        private const val MAXIMO_NOME = 60
        private const val MAXIMO_CURTO = 20
        private const val MAXIMO_UNIDADE = 12
        private const val MAXIMO_EQUACAO = 120
        private val VARIAVEIS_ACEITAS = setOf('A', 'B', 'C', 'D')

        /** Bytes do identificador por serviço de leitura. */
        private val SERVICOS = mapOf("01" to 1, "21" to 1, "22" to 2)
        private val HEX = Regex("^[0-9A-F]+$")
        private val CABECALHO_11_BITS = Regex("^[0-7][0-9A-F]{2}$")

        /** @throws IllegalArgumentException com a mensagem a mostrar ao usuário. */
        fun criar(
            nome: String,
            nomeCurto: String,
            modoPid: String,
            equacao: String,
            min: Double?,
            max: Double?,
            unidade: String,
            cabecalho: String?,
        ): PidCustomizado {
            // Sem separadores de CSV nem os delimitadores do cabeçalho de coluna do log.
            val nomeLimpo = limpar(nome, "[];,\"").take(MAXIMO_NOME)
            require(nomeLimpo.isNotEmpty()) { "nome em branco" }
            val modo = modoPid.uppercase(Locale.ROOT).replace("0X", "").filterNot { it.isWhitespace() }
            require(HEX.matches(modo)) { "ModeAndPID \"$modoPid\" não é hexadecimal" }
            val bytesId = requireNotNull(SERVICOS[modo.take(2)]) { "só os serviços de leitura 01, 21 e 22 são aceitos (veio \"$modo\")" }
            require(modo.length == 2 + bytesId * 2) { "o serviço ${modo.take(2)} pede um identificador de $bytesId byte(s)" }
            val texto = equacao.trim()
            require(texto.isNotEmpty()) { "equação em branco" }
            require(texto.length <= MAXIMO_EQUACAO) { "equação com mais de $MAXIMO_EQUACAO caracteres" }
            val formula = Formula.compilar(texto)
            require(formula.variaveis.isNotEmpty() && VARIAVEIS_ACEITAS.containsAll(formula.variaveis)) {
                "a equação precisa usar só os bytes A, B, C e D"
            }
            val menor = min ?: MIN_PADRAO
            val maior = max ?: MAX_PADRAO
            require(menor.isFinite() && maior.isFinite() && menor < maior) { "faixa Min/Max inválida" }
            val h = cabecalho?.uppercase(Locale.ROOT)?.filterNot { it.isWhitespace() }.orEmpty()
            val cabecalhoLimpo = when (h) {
                "", "AUTO", RequisicaoEstendida.CABECALHO_FUNCIONAL -> null
                else -> h.also { require(CABECALHO_11_BITS.matches(it)) { "cabeçalho \"$h\" não suportado (use 11 bits, ex.: 7E0, ou Auto)" } }
            }
            return PidCustomizado(
                nome = nomeLimpo,
                nomeCurto = limpar(nomeCurto, "[];,\"").take(MAXIMO_CURTO).ifEmpty { nomeLimpo.take(MAXIMO_CURTO) },
                modoPid = modo,
                equacao = texto,
                min = menor,
                max = maior,
                unidade = limpar(unidade, "()[];,\"").take(MAXIMO_UNIDADE),
                cabecalho = cabecalhoLimpo,
            )
        }

        private fun limpar(texto: String, proibidos: String): String =
            texto.map { if (it in proibidos || it.isISOControl()) ' ' else it }.joinToString("").trim().replace(Regex("\\s+"), " ")

        /** Junta [novos] aos [atuais] (mesma chave = substitui), até [MAXIMO_POR_VEICULO]. */
        fun mesclar(atuais: List<PidCustomizado>, novos: List<PidCustomizado>): List<PidCustomizado> =
            LinkedHashMap<String, PidCustomizado>()
                .apply { (atuais + novos).forEach { put(it.chave, it) } }
                .values.take(MAXIMO_POR_VEICULO)

        fun paraJson(pids: List<PidCustomizado>): JSONArray = JSONArray(
            pids.map { p ->
                JSONObject()
                    .put("nome", p.nome)
                    .put("nomeCurto", p.nomeCurto)
                    .put("modoPid", p.modoPid)
                    .put("equacao", p.equacao)
                    .put("min", p.min)
                    .put("max", p.max)
                    .put("unidade", p.unidade)
                    .put("cabecalho", p.cabecalho.orEmpty())
            },
        )

        /** @throws IllegalArgumentException se o objeto não descrever um PID válido. */
        fun deJson(o: JSONObject): PidCustomizado = criar(
            nome = o.optString("nome"),
            nomeCurto = o.optString("nomeCurto"),
            modoPid = o.optString("modoPid"),
            equacao = o.optString("equacao"),
            min = if (o.has("min")) o.optDouble("min") else null,
            max = if (o.has("max")) o.optDouble("max") else null,
            unidade = o.optString("unidade"),
            cabecalho = o.optString("cabecalho"),
        )

        /** Texto gravado no perfil do carro; null = nenhum PID (chave removida). */
        fun codificar(pids: List<PidCustomizado>): String? = pids.takeIf { it.isNotEmpty() }?.let { paraJson(it).toString() }

        /** Lê o perfil gravado: entradas inválidas são descartadas, nunca lança. */
        fun decodificar(texto: String?): List<PidCustomizado> {
            val lista = runCatching { JSONArray(texto.orEmpty()) }.getOrNull() ?: return emptyList()
            return mesclar(emptyList(), (0 until lista.length()).mapNotNull { runCatching { deJson(lista.getJSONObject(it)) }.getOrNull() })
        }
    }
}
