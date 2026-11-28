package com.charruamotors.ui.componentes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.charruamotors.R
import com.charruamotors.oficina.replay.PontoTrajeto
import com.charruamotors.oficina.replay.ProjecaoTrajeto
import com.charruamotors.oficina.replay.TrajetoGps
import com.google.android.material.color.MaterialColors

/**
 * Mini-mapa vetorial do trajeto em `Canvas`, sem mapa de fundo: a linha percorrida (norte para
 * cima), início e fim, os picos marcados e o ponto do cursor do gráfico.
 */
class TrajetoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Um pico destacado no trajeto, na cor da legenda. */
    data class Marca(val ponto: PontoTrajeto, val rotulo: String, val cor: Int)

    private var trajeto: TrajetoGps? = null
    private var marcas: List<Marca> = emptyList()
    private var projecao: ProjecaoTrajeto? = null
    private val caminho = Path()

    /** Posição do cursor do gráfico; null sem cursor ou fora do trajeto. */
    var cursor: PontoTrajeto? = null
        set(novo) {
            if (field == novo) return
            field = novo
            invalidate()
        }

    private val densidade = resources.displayMetrics.density
    private val corSuperficie = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
    private val corTexto = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val corLinha = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val corInicio = ContextCompat.getColor(context, R.color.status_ok)
    private val corFim = ContextCompat.getColor(context, R.color.status_pendente)

    private val pincelMoldura = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = densidade
        color = ColorUtils.setAlphaComponent(corTexto, 0x30)
    }
    private val pincelLinha = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * densidade
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = corLinha
    }
    private val pincelPonto = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pincelAnel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * densidade
    }
    private val pincelTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val pincelContorno = Paint(pincelTexto).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * densidade
        strokeJoin = Paint.Join.ROUND
        color = corSuperficie
    }

    fun definirTrajeto(novo: TrajetoGps?, novasMarcas: List<Marca>) {
        marcas = novasMarcas
        if (novo !== trajeto) {
            trajeto = novo
            montar()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        montar()
    }

    /** O traçado só muda com o trajeto ou o tamanho: o cursor redesenha sem recalculá-lo. */
    private fun montar() {
        caminho.reset()
        projecao = null
        val t = trajeto ?: return
        if (width == 0 || height == 0) return
        val p = ProjecaoTrajeto(t.pontos, width.toFloat(), height.toFloat(), MARGEM_DP * densidade)
        for (trecho in t.trechos) {
            var x = p.x(trecho.first())
            var y = p.y(trecho.first())
            caminho.moveTo(x, y)
            // Trecho de um ponto só: um traço mínimo para ele aparecer.
            if (trecho.size == 1) caminho.lineTo(x + 1f, y)
            for (i in 1 until trecho.size) {
                val nx = p.x(trecho[i])
                val ny = p.y(trecho[i])
                // Pontos a menos de 1 px do anterior não mudam o desenho.
                if (i < trecho.lastIndex && kotlin.math.abs(nx - x) < 1f && kotlin.math.abs(ny - y) < 1f) continue
                caminho.lineTo(nx, ny)
                x = nx
                y = ny
            }
        }
        projecao = p
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width - densidade, height - densidade, pincelMoldura)
        val t = trajeto ?: return
        val p = projecao ?: return
        canvas.drawPath(caminho, pincelLinha)
        pincelTexto.color = corTexto
        canvas.drawText("N ↑", 6 * densidade, pincelTexto.textSize + 4 * densidade, pincelTexto)

        pincelPonto.color = corInicio
        canvas.drawCircle(p.x(t.pontos.first()), p.y(t.pontos.first()), 5f * densidade, pincelPonto)
        pincelPonto.color = corFim
        canvas.drawCircle(p.x(t.pontos.last()), p.y(t.pontos.last()), 5f * densidade, pincelPonto)

        marcas.forEachIndexed { i, marca ->
            val x = p.x(marca.ponto)
            val y = p.y(marca.ponto)
            // Anéis de raios diferentes: picos no mesmo lugar continuam visíveis.
            pincelAnel.color = marca.cor
            canvas.drawCircle(x, y, (6f + 3f * i) * densidade, pincelAnel)
            val largura = pincelTexto.measureText(marca.rotulo)
            val xt = (x + 14 * densidade).coerceAtMost(width - largura - 2 * densidade).coerceAtLeast(0f)
            val yt = (y + (i - 1) * (pincelTexto.textSize + 2 * densidade)).coerceIn(pincelTexto.textSize, height - 3 * densidade)
            pincelTexto.color = marca.cor
            canvas.drawText(marca.rotulo, xt, yt, pincelContorno)
            canvas.drawText(marca.rotulo, xt, yt, pincelTexto)
        }

        cursor?.let {
            pincelPonto.color = corSuperficie
            canvas.drawCircle(p.x(it), p.y(it), 7.5f * densidade, pincelPonto)
            pincelPonto.color = corTexto
            canvas.drawCircle(p.x(it), p.y(it), 5.5f * densidade, pincelPonto)
        }
    }

    private companion object {
        const val MARGEM_DP = 18
    }
}
