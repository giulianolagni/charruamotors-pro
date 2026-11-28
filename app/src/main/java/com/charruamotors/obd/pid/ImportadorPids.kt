package com.charruamotors.obd.pid

import org.json.JSONArray
import org.json.JSONObject

/** PIDs aceitos e, para cada linha recusada, o motivo. */
data class ResultadoImportacao(val pids: List<PidCustomizado>, val erros: List<String>)

/** Importa PIDs customizados de um CSV do Torque Pro ou do JSON do app. Puro (JVM), nunca lança. */
object ImportadorPids {

    const val FORMATO_JSON = "charrua_pids"

    /** Arquivos maiores que isto não são listas de PIDs. */
    const val MAXIMO_CARACTERES = 512 * 1024

    /** Ordem das colunas do Torque: `Name,ShortName,ModeAndPID,Equation,Min,Max,Units,Header`. */
    private val COLUNAS = listOf("name", "shortname", "modeandpid", "equation", "min", "max", "units", "header")
    private const val OBRIGATORIAS = 4

    /** JSON se o conteúdo começar por `{` ou `[`; senão, CSV. */
    fun importar(texto: String): ResultadoImportacao {
        if (texto.length > MAXIMO_CARACTERES) return ResultadoImportacao(emptyList(), listOf("arquivo grande demais"))
        val limpo = texto.removePrefix("﻿").trim()
        if (limpo.isEmpty()) return ResultadoImportacao(emptyList(), listOf("arquivo vazio"))
        return if (limpo.startsWith("{") || limpo.startsWith("[")) deJson(limpo) else deCsvTorque(limpo)
    }

    fun deCsvTorque(texto: String): ResultadoImportacao {
        val linhas = texto.removePrefix("﻿").lines()
        val primeira = linhas.indexOfFirst { it.isNotBlank() }
        if (primeira < 0) return ResultadoImportacao(emptyList(), listOf("arquivo vazio"))
        val separador = if (';' in linhas[primeira] && ',' !in linhas[primeira]) ';' else ','
        val cabecalho = campos(linhas[primeira], separador).map { it.trim().lowercase().replace(" ", "") }
        val temCabecalho = "modeandpid" in cabecalho
        val indices = COLUNAS.mapIndexed { i, nome -> nome to if (temCabecalho) cabecalho.indexOf(nome) else i }.toMap()
        val pids = mutableListOf<PidCustomizado>()
        val erros = mutableListOf<String>()
        for (n in (if (temCabecalho) primeira + 1 else primeira) until linhas.size) {
            if (linhas[n].isBlank()) continue
            val c = campos(linhas[n], separador)
            fun campo(nome: String) = c.getOrNull(indices.getValue(nome))?.trim().orEmpty()
            try {
                require(c.size >= OBRIGATORIAS) { "linha incompleta (Name, ShortName, ModeAndPID e Equation são obrigatórios)" }
                pids += PidCustomizado.criar(
                    nome = campo("name"),
                    nomeCurto = campo("shortname"),
                    modoPid = campo("modeandpid"),
                    equacao = campo("equation"),
                    min = numero(campo("min"), "Min"),
                    max = numero(campo("max"), "Max"),
                    unidade = campo("units"),
                    cabecalho = campo("header"),
                )
            } catch (e: IllegalArgumentException) {
                erros += "Linha ${n + 1}: ${e.message}"
            }
        }
        return limitar(pids, erros)
    }

    fun deJson(texto: String): ResultadoImportacao {
        val lista: JSONArray = runCatching {
            if (texto.trimStart().startsWith("[")) {
                JSONArray(texto)
            } else {
                val raiz = JSONObject(texto)
                require(raiz.optString("formato") == FORMATO_JSON)
                raiz.getJSONArray("pids")
            }
        }.getOrNull() ?: return ResultadoImportacao(emptyList(), listOf("JSON inválido ou de outro formato"))
        val pids = mutableListOf<PidCustomizado>()
        val erros = mutableListOf<String>()
        for (i in 0 until lista.length()) {
            try {
                pids += PidCustomizado.deJson(requireNotNull(lista.optJSONObject(i)) { "item que não é um objeto" })
            } catch (e: IllegalArgumentException) {
                erros += "Item ${i + 1}: ${e.message}"
            }
        }
        return limitar(pids, erros)
    }

    /** O JSON do app, para compartilhar a lista de um carro. */
    fun exportar(pids: List<PidCustomizado>): String =
        JSONObject().put("formato", FORMATO_JSON).put("versao", 1).put("pids", PidCustomizado.paraJson(pids)).toString(2)

    private fun limitar(pids: List<PidCustomizado>, erros: List<String>): ResultadoImportacao {
        val unicos = pids.distinctBy { it.chave }
        val excesso = unicos.size - PidCustomizado.MAXIMO_POR_VEICULO
        return ResultadoImportacao(
            unicos.take(PidCustomizado.MAXIMO_POR_VEICULO),
            if (excesso > 0) erros + "$excesso PID(s) além do limite de ${PidCustomizado.MAXIMO_POR_VEICULO} por veículo" else erros,
        )
    }

    private fun numero(texto: String, coluna: String): Double? {
        if (texto.isEmpty()) return null
        return requireNotNull(texto.replace(',', '.').toDoubleOrNull()) { "$coluna \"$texto\" não é um número" }
    }

    /** Campos de uma linha CSV, com aspas (`""` = aspa literal). */
    private fun campos(linha: String, separador: Char): List<String> {
        val saida = mutableListOf<String>()
        val atual = StringBuilder()
        var entreAspas = false
        var i = 0
        while (i < linha.length) {
            val c = linha[i]
            when {
                c == '"' && entreAspas && linha.getOrNull(i + 1) == '"' -> { atual.append('"'); i++ }
                c == '"' -> entreAspas = !entreAspas
                c == separador && !entreAspas -> { saida += atual.toString(); atual.clear() }
                else -> atual.append(c)
            }
            i++
        }
        saida += atual.toString()
        return saida
    }
}
