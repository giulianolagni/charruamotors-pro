package com.charruamotors.ui.oficina.replay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.ActivityReplayLogBinding
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.ui.componentes.GraficoSeriesView
import com.charruamotors.ui.laudo.LaudoUi
import com.charruamotors.ui.laudo.ResultadoLaudoLog
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Replay Gráfico de um log CSV, aberto pela aba Histórico (sessão da lista ou CSV externo): até 4
 * colunas em [GraficoSeriesView], com zoom por pinça, arraste, cursor com horário e valores e o
 * resumo Mín/Máx/Média da janela visível. A lista de sessões vive só no Histórico (v2.10.0).
 * Não usa a conexão OBD2.
 */
class ReplayLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplayLogBinding
    private val viewModel: ReplayLogViewModel by viewModels { ReplayLogViewModel.Factory }
    private val coresSeries by lazy {
        listOf(R.color.serie_1, R.color.serie_2, R.color.serie_3, R.color.serie_4).map { ContextCompat.getColor(this, it) }
    }

    private var formatoTempo = FormatoTempo(true, 0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityReplayLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Também depois de o processo ser recriado: o ViewModel novo ainda não tem o log.
        if (viewModel.log.value == null) abrirDoIntent()

        binding.botaoVerTudo.setOnClickListener {
            viewModel.log.value?.let { binding.graficoReplay.definirJanela(it.total) }
        }
        binding.botaoGerarLaudo.setOnClickListener {
            viewModel.log.value?.let { log ->
                LaudoUi.pedirObservacoes(this) { viewModel.gerarLaudo(log.uri, log.nome, it) }
            }
        }
        binding.graficoReplay.ouvinte = object : GraficoSeriesView.Ouvinte {
            override fun aoMudarJanela(janela: JanelaReplay) = viewModel.mudarJanela(janela)
            override fun aoMoverCursor(indice: Int?) = viewModel.moverCursor(indice)
        }
        binding.graficoReplay.formatarTempo = { formatoTempo.eixo(it) }
        binding.graficoReplay.formatarValor = { _, v -> formatarValor(v) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.carregando.collect { binding.progressoCarregando.isInvisible = !it } }
                // Log ou seleção mudou: redesenha o gráfico com as séries novas.
                launch { combine(viewModel.log, viewModel.selecionados) { log, sel -> log to sel }.collect { (log, sel) -> mostrarLog(log, sel) } }
                launch { viewModel.resumo.collect(::mostrarResumo) }
                launch {
                    viewModel.eventos.collect { evento ->
                        when (evento) {
                            is EventoReplay.Erro -> mensagem(getString(R.string.replay_erro_ler, evento.detalhe))
                            EventoReplay.MaximoSensores -> mensagem(getString(R.string.replay_max_sensores, AnaliseReplay.MAX_SENSORES))
                            is EventoReplay.Laudo -> LaudoUi.mostrar(this@ReplayLogActivity, binding.root, evento.resultado)
                        }
                    }
                }
            }
        }
    }

    private fun abrirDoIntent() {
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return finish()
        val nome = intent.getStringExtra(EXTRA_NOME)
        if (nome == null) viewModel.abrirDocumento(uri) else viewModel.abrir(uri, nome)
    }

    private fun mostrarLog(aberto: LogAberto?, selecionados: List<Int>) = with(binding) {
        painelGrafico.isVisible = aberto != null
        if (aberto == null) return@with
        val log = aberto.log
        formatoTempo = FormatoTempo(log.tempoAbsoluto, log.inicioMs)
        toolbar.subtitle = aberto.nome
        textoNomeLog.definirTexto(aberto.nome)
        val info = getString(
            R.string.replay_info_log,
            log.amostras.toDouble().comoNumero(0),
            log.colunas.size,
            duracao(aberto.total.duracaoMs),
        )
        textoInfoLog.definirTexto(
            if (log.linhasIgnoradas > 0) info + " · " + getString(R.string.replay_linhas_ignoradas, log.linhasIgnoradas) else info,
        )
        montarChips(aberto, selecionados)
        val series = selecionados.mapIndexed { i, coluna ->
            GraficoSeriesView.Serie(log.colunas[coluna].rotulo, log.series[coluna], coresSeries[i % coresSeries.size])
        }
        textoSemSensor.isVisible = series.isEmpty()
        graficoReplay.definirDados(
            log.instantesMs,
            series,
            aberto.total,
            viewModel.janela.value ?: aberto.total,
            aberto.duracaoMinimaMs,
            viewModel.cursor.value,
        )
    }

    /** Um chip por coluna com leitura; as selecionadas ficam marcadas com a cor da série. */
    private fun montarChips(aberto: LogAberto, selecionados: List<Int>) {
        val grupo = binding.grupoSensores
        grupo.removeAllViews()
        val ordem = selecionados + aberto.log.colunasComDados.filter { it !in selecionados }
        for (coluna in ordem) {
            val posicao = selecionados.indexOf(coluna)
            val chip = Chip(this).apply {
                text = aberto.log.colunas[coluna].rotulo
                isCheckable = true
                isChecked = posicao >= 0
                if (posicao >= 0) {
                    chipStrokeWidth = 2 * resources.displayMetrics.density
                    chipStrokeColor = android.content.res.ColorStateList.valueOf(coresSeries[posicao % coresSeries.size])
                }
                // O estado marcado vem do ViewModel: o clique só pede a troca (o 5º sensor é recusado).
                setOnClickListener {
                    isChecked = posicao >= 0
                    viewModel.alternarSensor(coluna)
                }
            }
            grupo.addView(chip)
        }
    }

    private fun mostrarResumo(resumo: ResumoReplay?) = with(binding) {
        if (resumo == null) return@with
        val janela = resumo.janela
        textoJanela.definirTexto(
            getString(R.string.replay_janela, formatoTempo.eixo(janela.inicioMs), formatoTempo.eixo(janela.fimMs), duracao(janela.duracaoMs)),
        )
        textoCursor.definirTexto(
            resumo.instanteCursor?.let { getString(R.string.replay_cursor, formatoTempo.cursor(it)) } ?: getString(R.string.replay_cursor_vazio),
        )
        val lista = listaResumo
        while (lista.childCount > resumo.series.size) lista.removeViewAt(lista.childCount - 1)
        while (lista.childCount < resumo.series.size) {
            lista.addView(TextView(this@ReplayLogActivity).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                fontFeatureSettings = "tnum"
            })
        }
        resumo.series.forEachIndexed { i, s ->
            val texto = buildString {
                append("● ").append(s.definicao.rotulo)
                s.valorCursor?.let { append(": ").append(formatarValor(it)) }
                append("\n   ")
                val e = s.estatistica
                append(
                    if (e == null) getString(R.string.replay_sem_leituras)
                    else getString(R.string.replay_estatistica, formatarValor(e.minimo), formatarValor(e.maximo), formatarValor(e.media)),
                )
            }
            (lista.getChildAt(i) as TextView).apply {
                definirTexto(texto)
                setTextColor(coresSeries[s.posicao % coresSeries.size])
            }
        }
    }

    /** Casas decimais conforme a grandeza: 3.420 rpm, 101,3 kPa, 0,82 V. */
    private fun formatarValor(v: Double): String = v.comoNumero(
        when {
            abs(v) >= 100 -> 0
            abs(v) >= 10 -> 1
            else -> 2
        },
    )

    private fun duracao(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min ${s % 60} s"
            else -> "${(ms / 1000.0).comoNumero(1)} s"
        }
    }

    private fun mensagem(texto: String) {
        if (texto.isNotBlank()) Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()
    }

    /** Relógio (HH:mm:ss) quando o CSV tem `timestamp_ms`; senão o tempo desde o início (m:ss). */
    private class FormatoTempo(private val absoluto: Boolean, private val inicioMs: Long) {
        private val zona = ZoneId.systemDefault()
        private val eixo = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val cursor = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun eixo(t: Long): String = if (absoluto) eixo.format(Instant.ofEpochMilli(t).atZone(zona)) else relativo(t, false)
        fun cursor(t: Long): String = if (absoluto) cursor.format(Instant.ofEpochMilli(t).atZone(zona)) else relativo(t, true)

        private fun relativo(t: Long, ms: Boolean): String {
            val d = (t - inicioMs).coerceAtLeast(0)
            val base = "%d:%02d".format(d / 60_000, (d / 1000) % 60)
            return if (ms) base + ".%03d".format(d % 1000) else base
        }
    }

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_NOME = "extra_nome"

        /** Replay de um CSV da lista do Histórico. */
        fun intent(context: Context, uri: Uri, nome: String): Intent = intentExterno(context, uri).putExtra(EXTRA_NOME, nome)

        /** Replay de um documento do seletor do sistema: o nome é lido na abertura. */
        fun intentExterno(context: Context, uri: Uri): Intent =
            Intent(context, ReplayLogActivity::class.java).putExtra(EXTRA_URI, uri.toString())
    }
}
