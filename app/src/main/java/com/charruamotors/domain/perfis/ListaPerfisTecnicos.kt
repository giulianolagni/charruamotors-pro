package com.charruamotors.domain.perfis

import com.charruamotors.domain.faixas.PresetVeiculo
import java.text.Normalizer

/** Linha da tela Perfis Técnicos. */
sealed interface ItemPerfil {
    data class Secao(val titulo: String) : ItemPerfil

    /** [id] null = automático pelo cadastro. */
    data class Opcao(val id: String?, val titulo: String, val detalhe: String) : ItemPerfil
}

/**
 * A lista da tela Perfis Técnicos: automático, os genéricos de fallback e os perfis do catálogo
 * por Montadora → Motor/Família, com os validados em carro antes dos de base. Puro (JVM).
 */
object ListaPerfisTecnicos {

    const val SECAO_FALLBACK = "Genéricos (use quando o carro não estiver na lista)"
    private const val SUFIXO_BASE = "base, ainda sem validação completa em carro"

    /**
     * @param idBasico id do OBD-II básico (sem catálogo).
     * @param automatico título do perfil que o cadastro usaria.
     * @param busca por montadora, modelo ou motor; os genéricos continuam visíveis como saída.
     */
    fun montar(catalogo: CatalogoPerfis, idBasico: String, automatico: String, busca: String = ""): List<ItemPerfil> {
        val termos = normalizar(busca).split(' ').filter { it.isNotEmpty() }
        val especificos = catalogo.presets.filter { !it.generico }.filter { p ->
            val texto = normalizar("${p.marca} ${p.titulo} ${p.modelo} ${p.motor} ${p.perfilMotor.nome}")
            termos.all { it in texto }
        }
        return buildList {
            if (termos.isEmpty()) add(ItemPerfil.Opcao(null, "Automático pelo cadastro", automatico))
            add(ItemPerfil.Secao(SECAO_FALLBACK))
            addAll(fallbacks(catalogo, idBasico))
            val porStatus = especificos.sortedWith(compareBy({ it.status.ordinal }, { it.marca }, { it.motor }, { it.titulo }))
                .groupBy { it.status to it.marca }
            for ((chave, presets) in porStatus) {
                val (status, marca) = chave
                add(ItemPerfil.Secao(if (status == StatusPerfil.VALIDADO) marca else "$marca · $SUFIXO_BASE"))
                addAll(presets.map(::opcao))
            }
        }
    }

    private fun opcao(p: PresetVeiculo): ItemPerfil.Opcao = ItemPerfil.Opcao(
        p.id,
        "Motor ${p.motor} · ${p.titulo}",
        listOf(p.perfil.faixaAnos, p.status.rotulo).filter { it.isNotEmpty() }.joinToString(" · "),
    )

    /** Os 3 genéricos de fallback (o Flex tem a variante turbo); só os que existem no catálogo. */
    private fun fallbacks(catalogo: CatalogoPerfis, idBasico: String): List<ItemPerfil.Opcao> = listOfNotNull(
        ItemPerfil.Opcao(idBasico, "1. OBD-II / EOBD Básico", "Padrão universal, sem PIDs de montadora"),
        ItemPerfil.Opcao(
            IdentificadorPerfil.ID_FLEX_ASPIRADO,
            "2. OBD-II + CAN 11-bit Gasolina/Flex (pós-2004)",
            "Faixas de motor flex aspirado",
        ).takeIf { catalogo[IdentificadorPerfil.ID_FLEX_ASPIRADO] != null },
        ItemPerfil.Opcao(
            IdentificadorPerfil.ID_FLEX_TURBO,
            "2. OBD-II + CAN 11-bit Gasolina/Flex Turbo (pós-2004)",
            "Variante turbo: faixas e painel para motor turbo",
        ).takeIf { catalogo[IdentificadorPerfil.ID_FLEX_TURBO] != null },
        ItemPerfil.Opcao(
            IdentificadorPerfil.ID_DIESEL,
            "3. OBD-II + CAN 11-bit Diesel (pós-2004)",
            "Faixas de motor diesel common rail",
        ).takeIf { catalogo[IdentificadorPerfil.ID_DIESEL] != null },
    )

    private fun normalizar(texto: String): String =
        Normalizer.normalize(texto.trim(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
}
