package com.charruamotors.ui.componentes

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.charruamotors.R
import com.charruamotors.domain.alertas.EstadoAlertas
import com.charruamotors.ui.util.definirTexto

/**
 * v2.4.0: faixa vermelha piscante com os alertas em vigor e o botão de um toque "Silenciar 5 min".
 * A mesma no Painel, no Cluster e no HUD flutuante (por isso é montada em código, sem tema).
 * Some sozinha quando não há alerta.
 */
class BannerAlertaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Toque em "Silenciar 5 min". */
    var aoSilenciar: (() -> Unit)? = null

    private val fundo = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, R.color.hud_alerta))
        cornerRadius = dp(12).toFloat()
        setStroke(dp(2), Color.WHITE)
    }

    private val texto = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private val botao = TextView(context).apply {
        setText(R.string.alerta_silenciar)
        setTextColor(ContextCompat.getColor(context, R.color.status_pendente))
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(20).toFloat()
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { aoSilenciar?.invoke() }
    }

    private var piscar: ObjectAnimator? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = fundo
        addView(texto, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(botao, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isVisible = false
    }

    fun mostrar(estado: EstadoAlertas) {
        isVisible = estado.ativos.isNotEmpty()
        texto.definirTexto(estado.texto)
        botao.isVisible = estado.podeSilenciar
        if (isVisible) iniciarPisca() else pararPisca()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isVisible) iniciarPisca()
    }

    override fun onDetachedFromWindow() {
        pararPisca()
        super.onDetachedFromWindow()
    }

    /** Só o fundo pisca: o texto e o botão continuam sempre legíveis. */
    private fun iniciarPisca() {
        if (piscar != null) return
        piscar = ObjectAnimator.ofInt(fundo, "alpha", 255, 90).apply {
            duration = PISCA_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { invalidate() }
            start()
        }
    }

    private fun pararPisca() {
        piscar?.cancel()
        piscar = null
        fundo.alpha = 255
    }

    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

    private companion object {
        const val PISCA_MS = 450L
    }
}
