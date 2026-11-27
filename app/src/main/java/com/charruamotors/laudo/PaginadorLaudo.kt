package com.charruamotors.laudo

/** Estilo de uma linha do PDF: corpo da fonte e altura ocupada, em pontos. */
enum class EstiloLaudo(val tamanho: Float, val altura: Float, val negrito: Boolean = false) {
    TITULO(20f, 26f, negrito = true),
    CABECALHO(10f, 14f),
    DESTAQUE(13f, 24f, negrito = true),
    SECAO(12.5f, 26f, negrito = true),
    SUBTITULO(10.5f, 18f, negrito = true),
    ROTULO(10f, 14f, negrito = true),
    TEXTO(10f, 14f),
    RODAPE(8.5f, 12f),
}

/** Uma linha posicionada: [topo] a partir do alto da página e [x] a partir da borda esquerda. */
data class LinhaPdf(val texto: String, val estilo: EstiloLaudo, val x: Float, val topo: Float)

data class PaginaLaudo(val linhas: List<LinhaPdf>)

/** Largura de um texto num estilo, em pontos (no app, o `Paint`; nos testes, uma conta simples). */
fun interface MedidorTexto {
    fun largura(texto: String, estilo: EstiloLaudo): Float
}

/**
 * Quebra o [DocumentoLaudo] em páginas A4: cabeçalho completo na primeira, reduzido nas seguintes,
 * a faixa de destaque em todas, e o rodapé `Página X de Y`. Um título nunca fica sozinho no pé da
 * página, e um item curto não é partido entre duas.
 */
object PaginadorLaudo {

    const val LARGURA = 595
    const val ALTURA = 842
    const val MARGEM = 36f

    /** Deslocamento da coluna de valores dos campos. */
    const val COLUNA_VALOR = 150f
    const val RECUO_ITEM = 10f
    private const val FOLGA_COLUNA = 6f

    private const val LARGURA_UTIL = LARGURA - 2 * MARGEM
    private const val TOPO_RODAPE = ALTURA - MARGEM
    private const val FUNDO_CORPO = TOPO_RODAPE - 8f

    /** Blocos de até este tanto de linhas passam inteiros para a página seguinte. */
    private const val LINHAS_INDIVISIVEIS = 4

    fun paginar(doc: DocumentoLaudo, medidor: MedidorTexto): List<PaginaLaudo> {
        val paginas = mutableListOf<MutableList<LinhaPdf>>()
        var y = 0f

        fun linha(texto: String, estilo: EstiloLaudo, x: Float = MARGEM) {
            paginas.last() += LinhaPdf(texto, estilo, x, y)
            y += estilo.altura
        }

        fun novaPagina() {
            paginas += mutableListOf<LinhaPdf>()
            y = MARGEM
            if (paginas.size == 1) {
                linha(doc.titulo, EstiloLaudo.TITULO)
                doc.cabecalho.forEach { texto ->
                    quebrar(texto, EstiloLaudo.CABECALHO, LARGURA_UTIL, medidor).forEach { linha(it, EstiloLaudo.CABECALHO) }
                }
            } else {
                linha("${doc.titulo} — ${doc.cabecalho.firstOrNull().orEmpty()} (continuação)", EstiloLaudo.CABECALHO)
            }
            doc.faixaDestaque?.let {
                y += 4f
                linha(it, EstiloLaudo.DESTAQUE)
            }
            y += 6f
        }

        novaPagina()
        var inicioCorpo = y

        fun cabe(altura: Float) = y + altura <= FUNDO_CORPO
        fun virar() {
            novaPagina()
            inicioCorpo = y
        }

        doc.blocos.forEachIndexed { i, bloco ->
            val linhas = compor(bloco, medidor)
            val altura = linhas.sumOf { it.avanco.toDouble() }.toFloat()
            // Título leva junto o começo do que vem depois (inclusive um subtítulo logo abaixo).
            var reserva = altura
            if (bloco.ehTitulo) {
                var j = i + 1
                while (j < doc.blocos.size) {
                    val seguinte = compor(doc.blocos[j], medidor)
                    reserva += seguinte.take(if (doc.blocos[j].ehTitulo) seguinte.size else 2).sumOf { it.avanco.toDouble() }.toFloat()
                    if (!doc.blocos[j].ehTitulo) break
                    j++
                }
            }
            val indivisivel = bloco.ehTitulo || linhas.count { it.avanco > 0 } <= LINHAS_INDIVISIVEIS
            if (indivisivel && !cabe(reserva) && y > inicioCorpo) virar()
            linhas.forEach { parte ->
                // O marcador/rótulo (avanço 0) vira junto com o texto da mesma altura que o acompanha.
                if (!cabe(if (parte.avanco > 0) parte.avanco else parte.estilo.altura)) virar()
                paginas.last() += LinhaPdf(parte.texto, parte.estilo, parte.x, y)
                y += parte.avanco
            }
        }

        return paginas.mapIndexed { i, linhas ->
            PaginaLaudo(linhas + LinhaPdf("Página ${i + 1} de ${paginas.size}", EstiloLaudo.RODAPE, MARGEM, TOPO_RODAPE))
        }
    }

    /** Linha de um bloco ainda sem posição vertical; [avanco] 0 = divide a altura com a seguinte. */
    private class Parte(val texto: String, val estilo: EstiloLaudo, val x: Float, val avanco: Float = estilo.altura)

    private val BlocoLaudo.ehTitulo: Boolean get() = this is BlocoLaudo.Secao || this is BlocoLaudo.Subtitulo

    private fun compor(bloco: BlocoLaudo, medidor: MedidorTexto): List<Parte> {
        fun partes(texto: String, estilo: EstiloLaudo, x: Float = MARGEM) =
            quebrar(texto, estilo, LARGURA - MARGEM - x, medidor).map { Parte(it, estilo, x) }
        return when (bloco) {
            is BlocoLaudo.Secao -> partes(bloco.titulo, EstiloLaudo.SECAO)
            is BlocoLaudo.Subtitulo -> partes(bloco.texto, EstiloLaudo.SUBTITULO)
            is BlocoLaudo.Paragrafo -> partes(bloco.texto, EstiloLaudo.TEXTO)
            is BlocoLaudo.Item -> partes(bloco.texto, EstiloLaudo.TEXTO, MARGEM + RECUO_ITEM)
                .mapIndexed { i, p -> if (i == 0) listOf(Parte("•", EstiloLaudo.TEXTO, MARGEM, avanco = 0f), p) else listOf(p) }
                .flatten()
            is BlocoLaudo.Campo -> {
                // Rótulo que não cabe na coluna dele ganha a própria linha, sem invadir o valor.
                val aoLado = medidor.largura(bloco.rotulo, EstiloLaudo.ROTULO) <= COLUNA_VALOR - FOLGA_COLUNA
                val rotulo = if (aoLado) {
                    listOf(Parte(bloco.rotulo, EstiloLaudo.ROTULO, MARGEM, avanco = 0f))
                } else {
                    partes(bloco.rotulo, EstiloLaudo.ROTULO)
                }
                rotulo + partes(bloco.valor, EstiloLaudo.TEXTO, MARGEM + COLUNA_VALOR)
            }
        }
    }

    /** Quebra por palavras (respeitando as quebras do texto); palavra maior que a linha é cortada. */
    fun quebrar(texto: String, estilo: EstiloLaudo, largura: Float, medidor: MedidorTexto): List<String> =
        texto.replace("\r\n", "\n").split('\n').flatMap { paragrafo ->
            val linhas = mutableListOf<String>()
            var atual = ""
            for (palavra in paragrafo.split(' ', '\t').filter { it.isNotEmpty() }) {
                val junto = if (atual.isEmpty()) palavra else "$atual $palavra"
                if (medidor.largura(junto, estilo) <= largura) {
                    atual = junto
                    continue
                }
                if (atual.isNotEmpty()) linhas += atual
                var resto = palavra
                while (resto.length > 1 && medidor.largura(resto, estilo) > largura) {
                    var n = resto.length - 1
                    while (n > 1 && medidor.largura(resto.take(n), estilo) > largura) n--
                    linhas += resto.take(n)
                    resto = resto.drop(n)
                }
                atual = resto
            }
            linhas += atual
            linhas
        }
}
