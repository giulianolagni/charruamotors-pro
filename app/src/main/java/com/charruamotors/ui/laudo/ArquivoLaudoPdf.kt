package com.charruamotors.ui.laudo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.charruamotors.laudo.DadosLaudo
import com.charruamotors.laudo.EstiloLaudo
import com.charruamotors.laudo.LinhaPdf
import com.charruamotors.laudo.MedidorTexto
import com.charruamotors.laudo.MontadorLaudo
import com.charruamotors.laudo.PaginaLaudo
import com.charruamotors.laudo.PaginadorLaudo
import com.charruamotors.obd.log.RelatorioSessaoTxt
import com.charruamotors.ui.util.ExportadorCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId

/** PDF pronto para compartilhar: [uri] do FileProvider. */
data class LaudoGerado(val uri: Uri, val nome: String)

/**
 * Desenha o laudo com o [PdfDocument] do Android (A4) e o salva em `cacheDir/laudos`, exposto pelo
 * FileProvider. Recebe só o instantâneo [DadosLaudo]: não toca na sessão OBD nem nos perfis.
 */
class ArquivoLaudoPdf(context: Context) {

    private val contexto = context.applicationContext

    suspend fun gerar(dados: DadosLaudo): LaudoGerado = withContext(Dispatchers.IO) {
        val zona = ZoneId.systemDefault()
        val pinceis = EstiloLaudo.entries.associateWith(::pincel)
        val paginas = PaginadorLaudo.paginar(
            MontadorLaudo.montar(dados, zona),
            MedidorTexto { texto, estilo -> pinceis.getValue(estilo).measureText(texto) },
        )
        val pasta = File(contexto.cacheDir, PASTA)
        if (!pasta.isDirectory && !pasta.mkdirs()) throw IOException("não foi possível criar a pasta dos laudos")
        val arquivo = File(pasta, nomeArquivo(dados, zona))
        val pdf = PdfDocument()
        try {
            paginas.forEachIndexed { i, pagina ->
                val folha = pdf.startPage(
                    PdfDocument.PageInfo.Builder(PaginadorLaudo.LARGURA, PaginadorLaudo.ALTURA, i + 1).create(),
                )
                desenhar(folha.canvas, pagina, pinceis)
                pdf.finishPage(folha)
            }
            arquivo.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        // Só os mais recentes ficam guardados.
        pasta.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAXIMO_GUARDADOS).forEach { it.delete() }
        val uri = FileProvider.getUriForFile(contexto, "${contexto.packageName}${ExportadorCsv.SUFIXO_AUTORIDADE}", arquivo)
        LaudoGerado(uri, arquivo.name)
    }

    private fun desenhar(canvas: Canvas, pagina: PaginaLaudo, pinceis: Map<EstiloLaudo, Paint>) {
        val direita = PaginadorLaudo.LARGURA - PaginadorLaudo.MARGEM
        val fundo = Paint()
        pagina.linhas.forEach { linha: LinhaPdf ->
            val estilo = linha.estilo
            when (estilo) {
                EstiloLaudo.DESTAQUE -> {
                    fundo.color = COR_DESTAQUE
                    canvas.drawRect(PaginadorLaudo.MARGEM, linha.topo, direita, linha.topo + estilo.altura - 4f, fundo)
                }
                EstiloLaudo.SECAO -> {
                    fundo.color = COR_MARCA
                    val base = linha.topo + estilo.altura - 5f
                    canvas.drawRect(PaginadorLaudo.MARGEM, base, direita, base + 1f, fundo)
                }
                else -> Unit
            }
            val x = if (estilo == EstiloLaudo.DESTAQUE) linha.x + 8f else linha.x
            // A linha de base fica a um corpo de fonte abaixo do topo (a faixa tem folga própria).
            val recuo = if (estilo == EstiloLaudo.DESTAQUE) 1f else 0f
            val topoTexto = if (estilo == EstiloLaudo.SECAO) linha.topo + 5f else linha.topo
            canvas.drawText(linha.texto, x, topoTexto + estilo.tamanho + recuo, pinceis.getValue(estilo))
        }
    }

    private fun pincel(estilo: EstiloLaudo) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = estilo.tamanho
        typeface = Typeface.create(Typeface.SANS_SERIF, if (estilo.negrito) Typeface.BOLD else Typeface.NORMAL)
        color = when (estilo) {
            EstiloLaudo.TITULO, EstiloLaudo.SECAO -> COR_MARCA
            EstiloLaudo.DESTAQUE -> Color.WHITE
            EstiloLaudo.CABECALHO, EstiloLaudo.RODAPE -> Color.DKGRAY
            else -> Color.BLACK
        }
    }

    private fun nomeArquivo(dados: DadosLaudo, zona: ZoneId): String {
        val base = RelatorioSessaoTxt.nomeBase(dados.emitidoEmMs, dados.veiculo.perfil, zona, dados.simulado)
        return base.replaceFirst("CharruaOBD_", "Laudo_CharruaOBD_") + ".pdf"
    }

    private companion object {
        const val PASTA = "laudos"
        const val MAXIMO_GUARDADOS = 20
        val COR_MARCA = Color.rgb(13, 44, 84)
        val COR_DESTAQUE = Color.rgb(198, 40, 40)
    }
}
