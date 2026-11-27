package com.charruamotors.oficina.bateria

/** Resumo persistido de um teste concluído num carro real. */
data class RegistroTesteBateria(
    val quandoMs: Long,
    val repousoV: Double? = null,
    val partidaMinV: Double? = null,
    val partida: VereditoPartida? = null,
    val lentaV: Double? = null,
    val cargaV: Double? = null,
    val diesel: Boolean = false,
    val offsetV: Double = 0.0,
) {
    companion object {
        fun de(r: ResumoTesteBateria, quandoMs: Long) = RegistroTesteBateria(
            quandoMs = quandoMs,
            repousoV = r.repouso?.tensao,
            partidaMinV = r.partida?.vMin,
            partida = r.partida?.veredito,
            lentaV = r.lenta?.media,
            cargaV = r.carga?.media,
            diesel = r.diesel,
            offsetV = r.offsetV,
        )
    }
}

/** Últimos testes de um veículo (o mais recente primeiro), guardados no perfil isolado dele. */
data class HistoricoBateria(val testes: List<RegistroTesteBateria> = emptyList()) {

    fun com(registro: RegistroTesteBateria) = HistoricoBateria((listOf(registro) + testes).take(MAXIMO))

    /** Um teste por `|`; campos separados por `;` (vazio = não medido). Null = nada a gravar. */
    fun codificar(): String? = testes.takeIf { it.isNotEmpty() }?.joinToString("|") { t ->
        listOf(
            t.quandoMs, t.repousoV, t.partidaMinV, t.partida?.name, t.lentaV, t.cargaV, if (t.diesel) 1 else 0, t.offsetV,
        ).joinToString(";") { it?.toString().orEmpty() }
    }

    companion object {
        const val MAXIMO = 20

        /** Registros ilegíveis são descartados (nunca lança). */
        fun decodificar(texto: String?): HistoricoBateria = HistoricoBateria(
            texto.orEmpty().split('|').mapNotNull { linha ->
                val c = linha.split(';')
                val quando = c.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                if (c.size < 8) return@mapNotNull null
                fun volts(i: Int) = c[i].toDoubleOrNull()?.takeIf { it.isFinite() }
                RegistroTesteBateria(
                    quandoMs = quando,
                    repousoV = volts(1),
                    partidaMinV = volts(2),
                    partida = VereditoPartida.entries.firstOrNull { it.name == c[3] },
                    lentaV = volts(4),
                    cargaV = volts(5),
                    diesel = c[6] == "1",
                    offsetV = OffsetAdaptador.limitar(volts(7) ?: 0.0),
                )
            }.take(MAXIMO),
        )
    }
}
