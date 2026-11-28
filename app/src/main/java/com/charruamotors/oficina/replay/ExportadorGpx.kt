package com.charruamotors.oficina.replay

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** Ponto de interesse do GPX (os picos do trajeto). */
data class MarcoGpx(val nome: String, val ponto: PontoTrajeto)

/**
 * GPX 1.1 do trajeto, para o Google Earth, o Maps e afins: um `trkseg` por trecho contínuo, os picos
 * como `wpt` e o horário em UTC quando o log tem relógio real. Puro (JVM).
 */
object ExportadorGpx {

    fun gerar(nome: String, trajeto: TrajetoGps, tempoAbsoluto: Boolean, marcos: List<MarcoGpx> = emptyList()): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Charrua OBD Pro\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        // A ordem dos elementos é a do esquema: metadata, wpt, trk; em cada ponto ele, time, name.
        append("  <metadata><name>").append(escapar(nome)).append("</name></metadata>\n")
        for (marco in marcos) {
            abrirPonto("  ", "wpt", marco.ponto, tempoAbsoluto)
            append("<name>").append(escapar(marco.nome)).append("</name></wpt>\n")
        }
        append("  <trk>\n    <name>").append(escapar(nome)).append("</name>\n")
        for (trecho in trajeto.trechos) {
            append("    <trkseg>\n")
            var anterior: PontoTrajeto? = null
            for (p in trecho) {
                // O CSV repete o mesmo fix em várias linhas (a ECU lê mais rápido que o GPS).
                if (anterior?.latitude == p.latitude && anterior.longitude == p.longitude) continue
                abrirPonto("      ", "trkpt", p, tempoAbsoluto)
                append("</trkpt>\n")
                anterior = p
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private fun StringBuilder.abrirPonto(recuo: String, tag: String, p: PontoTrajeto, tempoAbsoluto: Boolean) {
        append(recuo).append('<').append(tag)
        append(" lat=\"").append(numero(p.latitude, 6)).append('"')
        append(" lon=\"").append(numero(p.longitude, 6)).append("\">")
        p.altitudeM?.let { append("<ele>").append(numero(it, 1)).append("</ele>") }
        if (tempoAbsoluto) append("<time>").append(Instant.ofEpochMilli(p.instanteMs)).append("</time>")
    }

    /** Ponto decimal e sem notação científica, em qualquer idioma do aparelho. */
    private fun numero(valor: Double, casas: Int): String = BigDecimal.valueOf(valor).setScale(casas, RoundingMode.HALF_UP).toPlainString()

    internal fun escapar(texto: String): String = buildString {
        for (c in texto) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // Caracteres de controle não são válidos em XML 1.0.
                else -> if (c >= ' ' || c == '\t' || c == '\n') append(c)
            }
        }
    }
}
