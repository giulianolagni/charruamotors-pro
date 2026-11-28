package com.charruamotors.obd.pid

/**
 * Fórmula aritmética de conversão de um PID, compilada uma única vez a partir do texto do catálogo
 * (ex.: `"(256*A+B)/4"`, `"A-40"`, `"(A-128)*100/128"`).
 *
 * Gramática: números decimais, `+ - * /`, parênteses, sinal unário e as variáveis:
 * - `A`..`H`: bytes de dados da resposta, na ordem (A = primeiro byte após o PID);
 * - `X`: valor numérico já lido em texto (comandos AT, ex.: `ATRV` → `12.6`);
 * - `Signed(x)`: inteiro com sinal, na largura dos bytes citados em `x` (`Signed(A)` = 8 bits,
 *   `Signed(256*A+B)` = 16 bits).
 *
 * Objeto puro (sem Android), avaliado a cada leitura sem reinterpretar o texto.
 */
class Formula private constructor(
    val texto: String,
    private val raiz: No,
    /** Variáveis usadas pela fórmula (ex.: `{A, B}`). */
    val variaveis: Set<Char>,
) {

    /** Quantidade mínima de bytes de dados que a fórmula exige (`B` → 2). */
    val bytesNecessarios: Int = variaveis.filter { it in BYTES }.maxOfOrNull { it - 'A' + 1 } ?: 0

    /**
     * Avalia com os [bytes] de dados (0..255) e, para comandos AT, o valor textual [x].
     * @throws IllegalArgumentException se faltarem bytes para as variáveis usadas.
     */
    fun avaliar(bytes: List<Int> = emptyList(), x: Double = 0.0): Double {
        require(bytes.size >= bytesNecessarios) {
            "Fórmula \"$texto\" precisa de $bytesNecessarios byte(s), recebeu ${bytes.size}"
        }
        return raiz.valor(bytes, x)
    }

    override fun toString() = texto

    // ---------------------------------------------------------------- árvore sintática

    private sealed interface No {
        fun valor(bytes: List<Int>, x: Double): Double
    }

    private class Constante(val v: Double) : No {
        override fun valor(bytes: List<Int>, x: Double) = v
    }

    private class VariavelByte(val indice: Int) : No {
        override fun valor(bytes: List<Int>, x: Double) = bytes[indice].toDouble()
    }

    private data object VariavelX : No {
        override fun valor(bytes: List<Int>, x: Double) = x
    }

    private class Negacao(val operando: No) : No {
        override fun valor(bytes: List<Int>, x: Double) = -operando.valor(bytes, x)
    }

    /** Complemento de dois em [bits]: da metade da faixa para cima o valor é negativo. */
    private class ComSinal(val operando: No, val bits: Int) : No {
        override fun valor(bytes: List<Int>, x: Double): Double {
            val v = operando.valor(bytes, x)
            val faixa = (1L shl bits).toDouble()
            return if (v >= faixa / 2 && v < faixa) v - faixa else v
        }
    }

    private class Binaria(val op: Char, val esq: No, val dir: No) : No {
        override fun valor(bytes: List<Int>, x: Double): Double {
            val a = esq.valor(bytes, x)
            val b = dir.valor(bytes, x)
            return when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                else -> a / b // divisão por zero gera ±Infinity/NaN, rejeitados pela validação de faixa
            }
        }
    }

    // ---------------------------------------------------------------- parser (descida recursiva)

    /** expressao := termo (('+'|'-') termo)* ; termo := fator (('*'|'/') fator)* ; fator := ('-'|'+') fator | numero | variavel | '(' expressao ')' */
    private class Analisador(private val fonte: String) {
        private var pos = 0
        val variaveis = linkedSetOf<Char>()

        /** Bytes citados desde a abertura do `Signed(` em análise (decide a largura). */
        private var citados = mutableSetOf<Char>()
        private var profundidade = 0

        fun analisar(): No {
            val no = expressao()
            pularEspacos()
            if (pos < fonte.length) erro("caractere inesperado '${fonte[pos]}'")
            return no
        }

        private fun expressao(): No {
            var no = termo()
            while (true) {
                val op = proximo() ?: return no
                if (op != '+' && op != '-') return no
                pos++
                no = Binaria(op, no, termo())
            }
        }

        private fun termo(): No {
            var no = fator()
            while (true) {
                val op = proximo() ?: return no
                if (op != '*' && op != '/') return no
                pos++
                no = Binaria(op, no, fator())
            }
        }

        private fun fator(): No {
            // Texto importado pelo usuário: o limite evita estouro de pilha na análise e na avaliação.
            if (++profundidade > PROFUNDIDADE_MAXIMA) erro("expressão aninhada demais")
            return fatorSimples().also { profundidade-- }
        }

        private fun comSinal(): No {
            pos += SIGNED.length
            if (proximo() != '(') erro("'(' esperado depois de Signed")
            val deFora = citados
            citados = mutableSetOf()
            val interno = fator()
            val bits = 8 * citados.size.coerceIn(1, 4)
            deFora += citados
            citados = deFora
            return ComSinal(interno, bits)
        }

        private fun fatorSimples(): No {
            val c = proximo() ?: erro("expressão incompleta")
            if (fonte.regionMatches(pos, SIGNED, 0, SIGNED.length, ignoreCase = true)) return comSinal()
            return when {
                c == '-' -> { pos++; Negacao(fator()) }
                c == '+' -> { pos++; fator() }
                c == '(' -> {
                    pos++
                    val interno = expressao()
                    if (proximo() != ')') erro("')' esperado")
                    pos++
                    interno
                }
                c.isDigit() || c == '.' -> numero()
                c.uppercaseChar() in BYTES -> {
                    pos++
                    variaveis += c.uppercaseChar()
                    citados += c.uppercaseChar()
                    VariavelByte(c.uppercaseChar() - 'A')
                }
                c.uppercaseChar() == 'X' -> { pos++; variaveis += 'X'; VariavelX }
                else -> erro("caractere inesperado '$c'")
            }
        }

        private fun numero(): No {
            val inicio = pos
            while (pos < fonte.length && (fonte[pos].isDigit() || fonte[pos] == '.')) pos++
            val v = fonte.substring(inicio, pos).toDoubleOrNull() ?: erro("número inválido")
            return Constante(v)
        }

        /** Próximo caractere não branco (sem consumir), ou null no fim. */
        private fun proximo(): Char? {
            pularEspacos()
            return fonte.getOrNull(pos)
        }

        private fun pularEspacos() {
            while (pos < fonte.length && fonte[pos].isWhitespace()) pos++
        }

        private fun erro(motivo: String): Nothing =
            throw IllegalArgumentException("Fórmula inválida \"$fonte\" (posição $pos): $motivo")
    }

    companion object {
        private val BYTES = 'A'..'H'
        private const val SIGNED = "SIGNED"
        private const val PROFUNDIDADE_MAXIMA = 40

        /** @throws IllegalArgumentException se o texto não for uma fórmula válida. */
        fun compilar(texto: String): Formula {
            val analisador = Analisador(texto)
            val raiz = analisador.analisar()
            return Formula(texto, raiz, analisador.variaveis)
        }
    }
}
