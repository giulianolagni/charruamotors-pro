package com.charruamotors.ui.laudo

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.charruamotors.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Partes de tela do laudo, comuns ao Diagnóstico de Erros e ao Visualizador de Logs. */
object LaudoUi {

    private const val MIME_PDF = "application/pdf"
    private const val MAXIMO_OBSERVACOES = 2000

    /** Diálogo rápido das observações (opcionais) antes de gerar o arquivo. */
    fun pedirObservacoes(activity: AppCompatActivity, aoConfirmar: (String) -> Unit) {
        val campo = TextInputEditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
        }
        val layout = TextInputLayout(activity).apply {
            hint = activity.getString(R.string.laudo_observacoes_dica)
            isCounterEnabled = true
            counterMaxLength = MAXIMO_OBSERVACOES
            addView(campo)
        }
        val margem = activity.resources.getDimensionPixelSize(R.dimen.margem_tela)
        val moldura = FrameLayout(activity).apply {
            setPadding(margem, margem / 2, margem, 0)
            addView(layout, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.laudo_observacoes_titulo)
            .setView(moldura)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.laudo_confirmar) { _, _ ->
                aoConfirmar(campo.text?.toString().orEmpty().take(MAXIMO_OBSERVACOES))
            }
            .show()
    }

    /**
     * Abre o compartilhamento nativo com o PDF (WhatsApp, e-mail...) e deixa na tela o atalho para o
     * visualizador de PDF.
     */
    fun entregar(activity: AppCompatActivity, raiz: View, laudo: LaudoGerado) {
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PDF
            putExtra(Intent.EXTRA_STREAM, laudo.uri)
            putExtra(Intent.EXTRA_SUBJECT, laudo.nome)
            clipData = ClipData.newRawUri(laudo.nome, laudo.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Snackbar.make(raiz, R.string.laudo_pronto, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.laudo_abrir) {
                val ver = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(laudo.uri, MIME_PDF)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    activity.startActivity(ver)
                } catch (_: ActivityNotFoundException) {
                    Snackbar.make(raiz, R.string.laudo_sem_leitor, Snackbar.LENGTH_LONG).show()
                }
            }
            .show()
        runCatching { activity.startActivity(Intent.createChooser(envio, activity.getString(R.string.laudo_compartilhar_titulo))) }
    }
}
