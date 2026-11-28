package com.charruamotors.ui.componentes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.charruamotors.R
import com.charruamotors.direcao.forcag.EstadoForcaG
import com.charruamotors.direcao.forcag.ForcaG
import com.charruamotors.ui.util.comoNumero
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/**
 * Mostrador circular de Força G em `Canvas`: anéis de 0,5, 1,0 e 1,5 G, o ponto ao vivo com rastro
 * curto e uma marca no pico de cada direção. O ponto segue a aceleração do carro: para cima
 * acelerando, para baixo freando, para o lado da curva.
 */
class ForcaGView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var estado: EstadoForcaG = EstadoForcaG()
        set(novo) {
            if (field == novo) return
            field = novo
            val atual = novo.atual
            contentDescription = context.getString(
                R.string.forca_g_mostrador_descricao,
                atual?.longitudinal?.comoNumero(2) ?: "—",
                atual?.lateral?.comoNumero(2) ?: "—",
            )
            invalidate()
        }

    private val densidade = resources.displayMetrics.density
    private val corLinha = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val corTexto = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val corPonto = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val corPico = ContextCompat.getColor(context, R.color.status_pendente)

    private val pincelAnel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = densidade
        color = ColorUtils.setAlphaComponent(corLinha, 0x40)
    }
    private val pincelRastro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * densidade
        strokeCap = Paint.Cap.ROUND
    }
    private val pincelPonto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = corPonto
    }
    private val pincelPico = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * densidade
        strokeCap = Paint.Cap.ROUND
        color = corPico
    }
    private val pincelTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        color = corTexto
        textAlign = Paint.Align.CENTER
    }

    private val rotulosAneis = ANEIS.map { it.comoNumero(1) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val folga = pincelTexto.textSize * 1.6f
        val raio = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / 2f - folga
        if (raio <= 0) return

        ANEIS.forEachIndexed { i, g ->
            val r = raio * (g / ESCALA_G).toFloat()
            canvas.drawCircle(cx, cy, r, pincelAnel)
            // Rótulo do anel na diagonal superior direita, onde o ponto passa menos.
            canvas.drawText(rotulosAneis[i], cx + r * DIAGONAL, cy - r * DIAGONAL, pincelTexto)
        }
        canvas.drawLine(cx - raio, cy, cx + raio, cy, pincelAnel)
        canvas.drawLine(cx, cy - raio, cx, cy + raio, pincelAnel)

        val meiaAltura = (pincelTexto.descent() + pincelTexto.ascent()) / 2
        canvas.drawText(context.getString(R.string.forca_g_acel), cx, cy - raio - folga / 2 - meiaAltura, pincelTexto)
        canvas.drawText(context.getString(R.string.forca_g_freio), cx, cy + raio + folga / 2 - meiaAltura, pincelTexto)
        canvas.drawText(context.getString(R.string.forca_g_esq), cx - raio - folga / 2, cy - meiaAltura, pincelTexto)
        canvas.drawText(context.getString(R.string.forca_g_dir), cx + raio + folga / 2, cy - meiaAltura, pincelTexto)

        val picos = estado.picos
        val marca = 7f * densidade
        fun passo(g: Double) = raio * (g.coerceIn(0.0, ESCALA_G) / ESCALA_G).toFloat()
        if (picos.aceleracao > MINIMO_PICO) (cy - passo(picos.aceleracao)).let { canvas.drawLine(cx - marca, it, cx + marca, it, pincelPico) }
        if (picos.frenagem > MINIMO_PICO) (cy + passo(picos.frenagem)).let { canvas.drawLine(cx - marca, it, cx + marca, it, pincelPico) }
        if (picos.curvaEsquerda > MINIMO_PICO) (cx - passo(picos.curvaEsquerda)).let { canvas.drawLine(it, cy - marca, it, cy + marca, pincelPico) }
        if (picos.curvaDireita > MINIMO_PICO) (cx + passo(picos.curvaDireita)).let { canvas.drawLine(it, cy - marca, it, cy + marca, pincelPico) }

        // Além do último anel o ponto fica preso na borda.
        fun x(f: ForcaG) = cx + raio * (f.lateral / maxOf(ESCALA_G, f.total)).toFloat()
        fun y(f: ForcaG) = cy - raio * (f.longitudinal / maxOf(ESCALA_G, f.total)).toFloat()

        val rastro = estado.rastro
        for (i in 1 until rastro.size) {
            pincelRastro.color = ColorUtils.setAlphaComponent(corPonto, 20 + 150 * i / rastro.size)
            canvas.drawLine(x(rastro[i - 1]), y(rastro[i - 1]), x(rastro[i]), y(rastro[i]), pincelRastro)
        }
        estado.atual?.let { canvas.drawCircle(x(it), y(it), 8f * densidade, pincelPonto) }
    }

    private companion object {
        val ANEIS = listOf(0.5, 1.0, 1.5)
        const val ESCALA_G = 1.5
        const val MINIMO_PICO = 0.02
        const val DIAGONAL = 0.7071f
    }
}
