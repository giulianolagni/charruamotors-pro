package com.charruamotors.ui.oficina.replay

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.charruamotors.ui.util.ExportadorCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** GPX pronto para abrir ou compartilhar: [uri] do FileProvider. */
data class GpxGerado(val uri: Uri, val nome: String)

/** Grava o GPX em `cacheDir/gpx`, exposto pelo FileProvider; só os mais recentes ficam guardados. */
class ArquivoGpx(context: Context) {

    private val contexto = context.applicationContext

    suspend fun salvar(nomeLog: String, conteudo: String): GpxGerado = withContext(Dispatchers.IO) {
        val pasta = File(contexto.cacheDir, PASTA)
        if (!pasta.isDirectory && !pasta.mkdirs()) throw IOException("não foi possível criar a pasta dos GPX")
        val base = nomeLog.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "trajeto" }
        val arquivo = File(pasta, "$base.gpx")
        arquivo.writeText(conteudo, Charsets.UTF_8)
        pasta.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAXIMO_GUARDADOS).forEach { it.delete() }
        GpxGerado(FileProvider.getUriForFile(contexto, "${contexto.packageName}${ExportadorCsv.SUFIXO_AUTORIDADE}", arquivo), arquivo.name)
    }

    private companion object {
        const val PASTA = "gpx"
        const val MAXIMO_GUARDADOS = 5
    }
}
