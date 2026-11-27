package com.charruamotors.direcao.desempenho

/** Um teste concluído, guardado no perfil do veículo. */
data class MarcaDesempenho(
    val modalidade: ModalidadeTeste,
    val tempoMs: Long,
    val velocidadeFinalKmh: Double,
    /** Quando o teste terminou (epoch ms). */
    val dataMs: Long,
)

/** Os [MAXIMO_POR_MODALIDADE] melhores tempos de cada modalidade, do menor para o maior. Puro (JVM). */
data class HistoricoDesempenho(val marcas: List<MarcaDesempenho> = emptyList()) {

    fun melhores(modalidade: ModalidadeTeste): List<MarcaDesempenho> =
        marcas.filter { it.modalidade == modalidade }.sortedBy { it.tempoMs }

    fun com(marca: MarcaDesempenho): HistoricoDesempenho {
        val daModalidade = (melhores(marca.modalidade) + marca).sortedBy { it.tempoMs }.take(MAXIMO_POR_MODALIDADE)
        return HistoricoDesempenho(marcas.filter { it.modalidade != marca.modalidade } + daModalidade)
    }

    fun sem(modalidade: ModalidadeTeste) = HistoricoDesempenho(marcas.filter { it.modalidade != modalidade })

    /** `0_100|9520|100.0|1790000000000;...`; null = vazio (chave removida). */
    fun codificar(): String? = marcas.takeIf { it.isNotEmpty() }
        ?.joinToString(";") { "${it.modalidade.codigo}|${it.tempoMs}|${it.velocidadeFinalKmh}|${it.dataMs}" }

    companion object {
        const val MAXIMO_POR_MODALIDADE = 10

        /** Entradas ilegíveis são descartadas (nunca lança). */
        fun decodificar(texto: String?): HistoricoDesempenho = texto.orEmpty().split(';').mapNotNull { item ->
            val partes = item.split('|')
            if (partes.size != 4) return@mapNotNull null
            val modalidade = ModalidadeTeste.deCodigo(partes[0]) ?: return@mapNotNull null
            val tempo = partes[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val velocidade = partes[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
            val data = partes[3].toLongOrNull() ?: return@mapNotNull null
            MarcaDesempenho(modalidade, tempo, velocidade, data)
        }.fold(HistoricoDesempenho()) { historico, marca -> historico.com(marca) }
    }
}
