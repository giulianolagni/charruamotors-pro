package com.charruamotors.ui.componentes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import com.charruamotors.R
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.oficina.replay.ReducaoSerie
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToLong

/**
 * Gráfico de séries no tempo, em `Canvas` sem bibliotecas — o mesmo para os Gráficos em Tempo Real e
 * o replay de CSV: até 4 séries, cada uma na própria escala (máximo, meio e mínimo do trecho visível
 * na cor dela), eixo de tempo, cursor vertical com o instante e downsampling min/máx por coluna de
 * pixel ([ReducaoSerie]). Trechos sem leitura (`NaN`) interrompem a curva.
 *
 * Dois modos:
 * - **navegável** ([definirDados], replay): pinça para zoom e arraste horizontal, toque posiciona o
 *   cursor (toque longo e arrastar o move) e toque duplo mostra tudo;
 * - **ao vivo** ([mostrarAoVivo]): a janela desliza sozinha e o dedo no gráfico é o cursor, que fica
 *   preso à posição da tela (e permanece depois de soltar com [manterCursor]).
 */
class GraficoSeriesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * Uma curva: [valores] alinhados aos instantes do gráfico. [escalaFixa] (mínimo, máximo) substitui a
     * escala pelo trecho visível, para curvas que precisam ser comparadas entre si.
     */
    class Serie(val rotulo: String, val valores: DoubleArray, val cor: Int, val escalaFixa: Pair<Double, Double>? = null)

    interface Ouvinte {
        fun aoMudarJanela(janela: JanelaReplay)
        fun aoMoverCursor(indice: Int?)
    }

    /** Navegação do replay. */
    var ouvinte: Ouvinte? = null

    /** O dedo moveu (ou soltou) o cursor no modo ao vivo. */
    var aoMoverCursorAoVivo: () -> Unit = {}

    /** Formata um instante para os rótulos do eixo (relógio ou tempo relativo). */
    var formatarTempo: (Long) -> String = { "${it / 1000} s" }

    /** Formata o instante no topo do cursor. */
    var formatarCursor: (Long) -> String = { formatarTempo(it) }

    /** Formata um valor da série [Int] nos rótulos de escala. */
    var formatarValor: (Int, Double) -> String = { _, v -> "%.1f".format(v) }

    private var aoVivo = false
    private var instantes = LongArray(0)
    private var series: List<Serie> = emptyList()
    private var total = JanelaReplay(0, 1)
    private var janela = JanelaReplay(0, 1)
    private var duracaoMinimaMs = AnaliseReplay.DURACAO_MINIMA_MS
    private var cursor: Int? = null

    /** Posição do dedo no modo ao vivo (0–1 da área útil). */
    private var fracaoCursor: Float? = null

    /** Modo ao vivo: com o gráfico pausado o cursor continua na tela depois de tirar o dedo. */
    var manterCursor: Boolean = false
        set(valor) {
            if (field == valor) return
            field = valor
            if (!valor) fracaoCursor = null
            invalidate()
        }

    /** Amostra sob o cursor (nos dois modos); null sem cursor ou sem amostras. */
    val indiceCursor: Int?
        get() {
            if (!aoVivo) return cursor
            val fracao = fracaoCursor ?: return null
            val alvo = janela.inicioMs + (janela.duracaoMs * fracao.toDouble()).roundToLong()
            return AnaliseReplay.indiceMaisProximo(instantes, alvo).takeIf { it >= 0 }
        }

    private val densidade = resources.displayMetrics.density
    private val corTexto = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val corCursor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val corGrade = ColorUtils.setAlphaComponent(corCursor, 0x22)

    private val pincelGrade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = densidade
        color = corGrade
    }
    private val pincelCurva = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * densidade
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pincelCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * densidade
        color = corCursor
    }
    private val pincelPonto = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pincelRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = corTexto
    }
    /** Contorno na cor do fundo: as escalas continuam legíveis com as curvas passando por baixo. */
    private val pincelContorno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * densidade
        strokeJoin = Paint.Join.ROUND
        textSize = sp(10f)
        color = MaterialColors.getColor(this@GraficoSeriesView, com.google.android.material.R.attr.colorSurface)
    }
    private val caminho = Path()

    private var arrastandoCursor = false

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, resources.displayMetrics)

    // ---------------------------------------------------------------- dados

    /** Replay: novo log ou nova seleção de colunas. */
    fun definirDados(
        novosInstantes: LongArray,
        novasSeries: List<Serie>,
        novoTotal: JanelaReplay,
        novaJanela: JanelaReplay,
        duracaoMinima: Long,
        indice: Int?,
    ) {
        aoVivo = false
        instantes = novosInstantes
        series = novasSeries
        total = novoTotal
        janela = AnaliseReplay.limitar(novaJanela, novoTotal)
        duracaoMinimaMs = duracaoMinima
        cursor = indice?.takeIf { it in instantes.indices }
        contentDescription = context.getString(R.string.replay_grafico_descricao, novasSeries.size)
        invalidate()
    }

    /** Ao vivo: o quadro atual da janela deslizante ([novaJanela] ocupa a largura toda, mesmo com poucas amostras). */
    fun mostrarAoVivo(novosInstantes: LongArray, novasSeries: List<Serie>, novaJanela: JanelaReplay) {
        aoVivo = true
        instantes = novosInstantes
        series = novasSeries
        total = novaJanela
        janela = novaJanela
        contentDescription = context.getString(R.string.graficos_grafico_descricao, novasSeries.size)
        invalidate()
    }

    /** Janela definida de fora (botão "Ver tudo" do replay). */
    fun definirJanela(nova: JanelaReplay) {
        janela = AnaliseReplay.limitar(nova, total)
        invalidate()
        ouvinte?.aoMudarJanela(janela)
    }

    // ---------------------------------------------------------------- área útil

    private val esquerda get() = paddingLeft + MARGEM_ESQUERDA_DP * densidade
    private val direita get() = width - paddingRight - MARGEM_DIREITA_DP * densidade
    private val topo get() = paddingTop + MARGEM_TOPO_DP * densidade
    private val base get() = height - paddingBottom - MARGEM_BASE_DP * densidade

    private fun xDe(t: Long): Float = esquerda + (direita - esquerda) * ((t - janela.inicioMs).toFloat() / janela.duracaoMs.coerceAtLeast(1))

    private fun tempoDe(x: Float): Long =
        janela.inicioMs + ((x - esquerda) / (direita - esquerda).coerceAtLeast(1f) * janela.duracaoMs).toDouble().roundToLong()

    // ---------------------------------------------------------------- gestos

    private val escala = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // Zoom só na linha do tempo: a abertura horizontal dos dedos; dedos na vertical, o fator geral.
            val fator = if (detector.previousSpanX > SPAN_MINIMO_DP * densidade) {
                detector.currentSpanX / detector.previousSpanX
            } else {
                detector.scaleFactor
            }
            if (fator.isFinite() && fator > 0f) mudarJanela(AnaliseReplay.zoom(janela, total, fator.toDouble(), tempoDe(detector.focusX), duracaoMinimaMs))
            return true
        }
    })

    private val gestos = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (escala.isInProgress || arrastandoCursor || e2.pointerCount > 1) return false
            val msPorPixel = janela.duracaoMs / (direita - esquerda).coerceAtLeast(1f)
            mudarJanela(AnaliseReplay.deslocar(janela, total, (distanceX * msPorPixel).toDouble().roundToLong()))
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            moverCursor(e.x)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            mudarJanela(total)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            arrastandoCursor = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            moverCursor(e.x)
        }
    })

    @SuppressLint("ClickableViewAccessibility") // o gráfico não tem ação de clique; o cursor é só leitura
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (instantes.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val soltou = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (aoVivo) {
            fracaoCursor = when {
                !soltou -> ((event.x - esquerda) / (direita - esquerda).coerceAtLeast(1f)).coerceIn(0f, 1f)
                manterCursor -> fracaoCursor
                else -> null
            }
            invalidate()
            aoMoverCursorAoVivo()
        } else {
            escala.onTouchEvent(event)
            if (arrastandoCursor && event.actionMasked == MotionEvent.ACTION_MOVE) moverCursor(event.x)
            gestos.onTouchEvent(event)
        }
        if (soltou) {
            arrastandoCursor = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun mudarJanela(nova: JanelaReplay) {
        if (nova == janela) return
        janela = nova
        invalidate()
        ouvinte?.aoMudarJanela(nova)
    }

    private fun moverCursor(x: Float) {
        if (instantes.isEmpty()) return
        val indice = AnaliseReplay.indiceMaisProximo(instantes, tempoDe(x.coerceIn(esquerda, direita)))
        if (indice == cursor) return
        cursor = indice
        invalidate()
        ouvinte?.aoMoverCursor(indice)
    }

    // ---------------------------------------------------------------- desenho

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (direita <= esquerda || base <= topo) return

        // Grade horizontal discreta (as escalas são por série) e eixo de tempo com 4 marcas.
        for (i in 0..4) {
            val y = base - (base - topo) * i / 4
            canvas.drawLine(esquerda, y, direita, y, pincelGrade)
        }
        for (i in 1..2) {
            val x = esquerda + (direita - esquerda) * i / 3
            canvas.drawLine(x, topo, x, base, pincelGrade)
        }
        if (instantes.isEmpty()) return
        pincelRotulo.color = corTexto
        for (i in 0..3) {
            val x = esquerda + (direita - esquerda) * i / 3
            pincelRotulo.textAlign = when (i) {
                0 -> Paint.Align.LEFT
                3 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(formatarTempo(tempoDe(x)), x, base + pincelRotulo.textSize + 4 * densidade, pincelRotulo)
        }

        val faixa = AnaliseReplay.faixaIndices(instantes, janela)
        // Um ponto além de cada borda, para a curva sair do quadro em vez de parar antes dele.
        val de = (faixa.first - 1).coerceAtLeast(0)
        val ate = (faixa.last + 1).coerceAtMost(instantes.lastIndex)

        canvas.save()
        canvas.clipRect(esquerda, topo - pincelCurva.strokeWidth, direita, base + pincelCurva.strokeWidth)
        val escalas = series.map { serie ->
            serie.escalaFixa ?: AnaliseReplay.estatisticas(serie.valores, faixa)?.let(AnaliseReplay::escala)
        }
        series.forEachIndexed { s, serie ->
            val (minimo, maximo) = escalas[s] ?: return@forEachIndexed
            montarCaminho(serie.valores, de, ate, minimo, maximo)
            pincelCurva.color = serie.cor
            canvas.drawPath(caminho, pincelCurva)
        }
        canvas.restore()

        desenharEscalas(canvas, escalas)
        desenharCursor(canvas, escalas)
    }

    /** Uma coluna de rótulos por série, na cor dela: o topo, o meio e a base da escala própria. */
    private fun desenharEscalas(canvas: Canvas, escalas: List<Pair<Double, Double>?>) {
        pincelRotulo.textAlign = Paint.Align.LEFT
        var deslocamento = 2 * densidade
        series.forEachIndexed { s, serie ->
            val (minimo, maximo) = escalas[s] ?: return@forEachIndexed
            pincelRotulo.color = serie.cor
            val textos = listOf(
                formatarValor(s, maximo) to topo + pincelRotulo.textSize,
                formatarValor(s, (minimo + maximo) / 2) to (topo + base) / 2 - 2 * densidade,
                formatarValor(s, minimo) to base - 3 * densidade,
            )
            for ((texto, y) in textos) {
                canvas.drawText(texto, esquerda + deslocamento, y, pincelContorno)
                canvas.drawText(texto, esquerda + deslocamento, y, pincelRotulo)
            }
            deslocamento += textos.maxOf { pincelRotulo.measureText(it.first) } + 10 * densidade
        }
    }

    private fun desenharCursor(canvas: Canvas, escalas: List<Pair<Double, Double>?>) {
        val i = indiceCursor ?: return
        val t = instantes.getOrNull(i) ?: return
        if (t < janela.inicioMs || t > janela.fimMs) return
        val x = xDe(t)
        canvas.drawLine(x, topo, x, base, pincelCursor)
        series.forEachIndexed { s, serie ->
            val v = serie.valores.getOrNull(i)?.takeUnless { it.isNaN() } ?: return@forEachIndexed
            val (minimo, maximo) = escalas[s] ?: return@forEachIndexed
            val y = base - (base - topo) * AnaliseReplay.normalizar(v, minimo, maximo).toFloat()
            pincelPonto.color = serie.cor
            canvas.drawCircle(x, y, 4.5f * densidade, pincelPonto)
        }
        // Horário exato no topo da linha, dentro do quadro.
        val texto = formatarCursor(t)
        val largura = pincelRotulo.measureText(texto)
        pincelRotulo.color = corCursor
        pincelRotulo.textAlign = Paint.Align.LEFT
        val xt = (x - largura / 2).coerceIn(esquerda, (direita - largura).coerceAtLeast(esquerda))
        canvas.drawText(texto, xt, paddingTop + pincelRotulo.textSize, pincelRotulo)
    }

    /** Caminho de uma série entre os índices [de]..[ate], já reduzido à largura em pixels. */
    private fun montarCaminho(valores: DoubleArray, de: Int, ate: Int, minimo: Double, maximo: Double) {
        caminho.reset()
        val altura = base - topo
        val pontos = ReducaoSerie.reduzir(instantes, valores, de, ate, janela, (direita - esquerda).toInt())
        pontos.forEachIndexed { i, p ->
            val x = xDe(instantes[p.indice])
            val y = base - altura * AnaliseReplay.normalizar(valores[p.indice], minimo, maximo).toFloat()
            if (p.novoTraco) {
                caminho.moveTo(x, y)
                // Leitura isolada entre falhas: um traço mínimo para ela aparecer.
                if (pontos.getOrNull(i + 1)?.novoTraco != false) caminho.lineTo(x + 1f, y)
            } else {
                caminho.lineTo(x, y)
            }
        }
    }

    companion object {
        /** Cores do osciloscópio ao vivo (fundo escuro): ciano, amarelo, magenta e verde. */
        val CORES_AO_VIVO = intArrayOf(
            0xFF00E5FF.toInt(), 0xFFFFD600.toInt(), 0xFFFF4081.toInt(), 0xFF76FF03.toInt(),
        )
        private const val MARGEM_ESQUERDA_DP = 4
        private const val MARGEM_DIREITA_DP = 4
        private const val MARGEM_TOPO_DP = 18
        private const val MARGEM_BASE_DP = 18
        private const val SPAN_MINIMO_DP = 40
    }
}
