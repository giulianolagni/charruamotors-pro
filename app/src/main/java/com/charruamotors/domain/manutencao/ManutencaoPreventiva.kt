package com.charruamotors.domain.manutencao

import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.log.RelatorioSessaoTxt
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** Atalhos de cadastro. Os intervalos são só uma sugestão editável (o manual do carro manda). */
enum class PresetManutencao(val titulo: String, val intervaloKm: Int?, val intervaloMeses: Int?) {
    OLEO_MOTOR("Óleo do motor", 10_000, 12),
    FILTRO_OLEO("Filtro de óleo", 10_000, 12),
    FILTRO_COMBUSTIVEL("Filtro de combustível/diesel", 20_000, 12),
    FILTRO_AR("Filtro de ar", 20_000, 12),
    FLUIDO_CAMBIO("Fluido ATF/câmbio", 60_000, 48),
    CORREIA("Correia dentada/acessórios", 100_000, 60),
    PASTILHAS("Pastilhas de freio", 30_000, null),
    PERSONALIZADO("Personalizado", null, null),
}

/** Item da manutenção preventiva de um carro. Sem intervalo (km nem meses) nunca vence. */
data class ItemManutencao(
    /** 0 = ainda não salvo. */
    val id: Long = 0,
    val nome: String,
    val kmUltimaTroca: Int,
    val dataUltimaTroca: LocalDate,
    val intervaloKm: Int? = null,
    val intervaloMeses: Int? = null,
    val observacao: String = "",
)

/** Linha do histórico enxuto de trocas. */
data class RegistroTroca(val itemId: Long, val nome: String, val km: Int, val data: LocalDate)

/** Do mais saudável ao pior (a ordem decide qual critério vence). */
enum class StatusManutencao(val titulo: String) {
    EM_DIA("Em dia"),
    PROXIMO("Próximo"),
    VENCIDO("Vencido"),
}

/** Situação de um item: o pior entre km e data. Restantes negativos = passou do ponto; null = sem esse intervalo. */
data class SituacaoManutencao(val status: StatusManutencao, val restanteKm: Int?, val restanteDias: Long?) {

    /** `faltam 800 km · vence em 20 dias`, `passou 120 km`, `sem intervalo definido`. */
    val descricao: String
        get() = listOfNotNull(
            restanteKm?.let {
                when {
                    it > 0 -> "faltam ${milhar(it.toLong())} km"
                    it < 0 -> "passou ${milhar(abs(it.toLong()))} km"
                    else -> "vence nesta quilometragem"
                }
            },
            restanteDias?.let {
                when {
                    it > 0 -> "vence em ${dias(it)}"
                    it < 0 -> "venceu há ${dias(-it)}"
                    else -> "vence hoje"
                }
            },
        ).joinToString(" · ").ifEmpty { "sem intervalo definido" }

    private fun dias(n: Long) = if (n == 1L) "1 dia" else "${milhar(n)} dias"
    private fun milhar(n: Long) = NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR")).format(n)
}

data class ItemComSituacao(val item: ItemManutencao, val situacao: SituacaoManutencao)

/** Itens e histórico de trocas de **um** carro (vive no perfil isolado). Puro (JVM). */
data class PlanoManutencao(
    val itens: List<ItemManutencao> = emptyList(),
    /** Da troca mais recente para a mais antiga, no máximo [MAX_HISTORICO]. */
    val historico: List<RegistroTroca> = emptyList(),
) {
    /** Inclui (id 0) ou substitui o item. */
    fun com(item: ItemManutencao): PlanoManutencao {
        if (item.id == 0L) {
            // O histórico também reserva ids: um item novo não herda as trocas de um removido.
            val maior = maxOf(itens.maxOfOrNull { it.id } ?: 0, historico.maxOfOrNull { it.itemId } ?: 0)
            return copy(itens = itens + item.copy(id = maior + 1))
        }
        return copy(itens = itens.map { if (it.id == item.id) item else it })
    }

    /** Remove o item; as trocas já registradas ficam no histórico. */
    fun sem(id: Long): PlanoManutencao = copy(itens = itens.filterNot { it.id == id })

    /** "Registrar troca agora": o item passa a contar de [km]/[data] e a troca entra no histórico. */
    fun registrarTroca(id: Long, km: Int, data: LocalDate): PlanoManutencao {
        val item = itens.firstOrNull { it.id == id } ?: return this
        return com(item.copy(kmUltimaTroca = km, dataUltimaTroca = data))
            .copy(historico = (listOf(RegistroTroca(id, item.nome, km, data)) + historico).take(MAX_HISTORICO))
    }

    /** Itens com a situação, os mais urgentes primeiro. */
    fun avaliar(odometroKm: Int, hoje: LocalDate): List<ItemComSituacao> =
        itens.map { ItemComSituacao(it, situacao(it, odometroKm, hoje)) }
            .sortedByDescending { it.situacao.status }

    /** Só os vencidos e próximos. */
    fun pendencias(odometroKm: Int, hoje: LocalDate): List<ItemComSituacao> =
        avaliar(odometroKm, hoje).filter { it.situacao.status != StatusManutencao.EM_DIA }

    /** Resumo curto (nome → situação) para o laudo; vazio sem pendências. */
    fun linhasDoLaudo(odometroKm: Int, hoje: LocalDate): List<Pair<String, String>> =
        pendencias(odometroKm, hoje).map { it.item.nome to "${it.situacao.status.titulo} · ${it.situacao.descricao}" }

    /** Null sem nada a guardar. */
    fun codificar(): String? {
        if (itens.isEmpty() && historico.isEmpty()) return null
        return JSONObject()
            .put(
                "itens",
                JSONArray(
                    itens.map { i ->
                        JSONObject()
                            .put("id", i.id)
                            .put("nome", i.nome)
                            .put("km", i.kmUltimaTroca)
                            .put("data", i.dataUltimaTroca.toString())
                            .putOpt("intervaloKm", i.intervaloKm)
                            .putOpt("intervaloMeses", i.intervaloMeses)
                            .put("obs", i.observacao)
                    },
                ),
            )
            .put(
                "historico",
                JSONArray(
                    historico.map { r ->
                        JSONObject().put("itemId", r.itemId).put("nome", r.nome).put("km", r.km).put("data", r.data.toString())
                    },
                ),
            )
            .toString()
    }

    companion object {
        const val MAX_HISTORICO = 100

        /** "Próximo" com até esta fração do intervalo em km pela frente... */
        const val FRACAO_PROXIMO_KM = 0.10

        /** ...ou com até estes dias pela frente. */
        const val DIAS_PROXIMO = 30L

        fun situacao(item: ItemManutencao, odometroKm: Int, hoje: LocalDate): SituacaoManutencao {
            val intervaloKm = item.intervaloKm?.takeIf { it > 0 }
            val restanteKm = intervaloKm?.let { item.kmUltimaTroca + it - odometroKm }
            val restanteDias = item.intervaloMeses?.takeIf { it > 0 }
                ?.let { ChronoUnit.DAYS.between(hoje, item.dataUltimaTroca.plusMonths(it.toLong())) }
            val porKm = when {
                restanteKm == null -> StatusManutencao.EM_DIA
                restanteKm <= 0 -> StatusManutencao.VENCIDO
                restanteKm <= intervaloKm * FRACAO_PROXIMO_KM -> StatusManutencao.PROXIMO
                else -> StatusManutencao.EM_DIA
            }
            val porData = when {
                restanteDias == null -> StatusManutencao.EM_DIA
                restanteDias <= 0 -> StatusManutencao.VENCIDO
                restanteDias <= DIAS_PROXIMO -> StatusManutencao.PROXIMO
                else -> StatusManutencao.EM_DIA
            }
            return SituacaoManutencao(maxOf(porKm, porData), restanteKm, restanteDias)
        }

        /** Texto ausente ou inválido = plano vazio; um registro estragado não derruba os outros. */
        fun decodificar(texto: String?): PlanoManutencao {
            if (texto.isNullOrBlank()) return PlanoManutencao()
            val raiz = runCatching { JSONObject(texto) }.getOrNull() ?: return PlanoManutencao()
            fun <T> lista(nome: String, ler: (JSONObject) -> T): List<T> {
                val array = raiz.optJSONArray(nome) ?: return emptyList()
                return (0 until array.length()).mapNotNull { runCatching { ler(array.getJSONObject(it)) }.getOrNull() }
            }
            return PlanoManutencao(
                itens = lista("itens") { o ->
                    ItemManutencao(
                        id = o.getLong("id"),
                        nome = o.getString("nome"),
                        kmUltimaTroca = o.getInt("km"),
                        dataUltimaTroca = LocalDate.parse(o.getString("data")),
                        intervaloKm = o.optInt("intervaloKm", 0).takeIf { it > 0 },
                        intervaloMeses = o.optInt("intervaloMeses", 0).takeIf { it > 0 },
                        observacao = o.optString("obs"),
                    )
                }.distinctBy { it.id },
                historico = lista("historico") { o ->
                    RegistroTroca(o.getLong("itemId"), o.getString("nome"), o.getInt("km"), LocalDate.parse(o.getString("data")))
                }.take(MAX_HISTORICO),
            )
        }
    }
}

/**
 * Odômetro do carro: [km] soma as viagens OBD2 reais em cima da última referência. [kmCadastro] é a
 * quilometragem do cadastro quando a referência foi definida: se o cadastro mudar (abastecimento,
 * serviço ou edição), a leitura nova do painel do carro passa a valer.
 */
data class Odometro(val kmCadastro: Int, val km: Double) {

    fun codificar(): String = "$kmCadastro;$km"

    companion object {
        /** Quilometragem a mostrar: a do odômetro, se ainda se refere a este cadastro. */
        fun atual(odometro: Odometro?, kmCadastro: Int): Int =
            odometro?.takeIf { it.kmCadastro == kmCadastro }?.km?.toInt() ?: kmCadastro

        fun decodificar(texto: String?): Odometro? {
            val partes = texto?.split(';')?.takeIf { it.size == 2 } ?: return null
            val cadastro = partes[0].toIntOrNull() ?: return null
            val km = partes[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
            return Odometro(cadastro, km)
        }
    }
}

/** De qual carro é um log salvo: pela placa do relatório ou, sem ela, pelo nome do arquivo. */
object VeiculoDoLog {
    fun identificar(placa: String?, nomeArquivo: String, veiculos: List<Veiculo>): Veiculo? {
        fun normalizar(p: String) = p.filter(Char::isLetterOrDigit).uppercase()
        if (!placa.isNullOrBlank()) return veiculos.firstOrNull { normalizar(it.placa) == normalizar(placa) }
        val base = nomeArquivo.substringBeforeLast('.')
        // Dois carros iguais no cadastro: sem a placa, não dá para saber qual é.
        return veiculos.singleOrNull {
            val trecho = RelatorioSessaoTxt.trechoArquivo("${it.marca} ${it.modelo}".trim())
            Regex("^CharruaOBD_(SIMULADO_)?\\d{8}_\\d{6}_${Regex.escape(trecho)}$").matches(base)
        }
    }
}
