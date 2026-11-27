package com.charruamotors.ui.oficina.injecao

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityInjecaoBinding
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.oficina.injecao.EstadoInjecao
import com.charruamotors.oficina.injecao.LeituraBanco
import com.charruamotors.oficina.injecao.PontoSondas
import com.charruamotors.oficina.injecao.SuporteBanco
import com.charruamotors.oficina.injecao.TextosInjecao
import com.charruamotors.oficina.injecao.TipoSondaPre
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.ui.componentes.GraficoSeriesView
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Oficina → Diagnóstico de Injeção: Fuel Trims por banco (lenta × carga), sondas O2 pré e
 * pós-catalisador e o Freeze Frame (modo 02). Enquanto visível, o polling prioriza os PIDs suportados.
 */
class InjecaoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInjecaoBinding
    private val viewModel: InjecaoViewModel by viewModels { InjecaoViewModel.Factory }
    private val app get() = application as CharruaMotorsApp
    private var ultimoPonto: PontoSondas? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityInjecaoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.graficoSondas.formatarValor = { _, v -> v.comoNumero(2) }
        binding.botaoRecarregarFf.setOnClickListener { viewModel.recarregar() }
        // Atalho do Diagnóstico de Erros: abre direto no Freeze Frame.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_FREEZE_FRAME, false)) {
            binding.cardFreezeFrame.doOnLayout { binding.conteudo.scrollTo(0, it.top) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val sessao = app.sessaoObd
                // Registra o foco ao abrir e de novo quando o que o carro suporta muda.
                launch {
                    combine(sessao.pidsSuportados, sessao.infoConexao, sessao.estado) { _, _, estado -> estado }.collect { estado ->
                        val conectado = estado is EstadoConexaoObd.Conectado
                        // Sem conexão não se sabe o que o carro suporta: ficam as últimas leituras (ou nada).
                        if (conectado) sessao.abrirInjecao(this@InjecaoActivity)
                        binding.textoSemConexao.isVisible = !conectado
                        binding.grupoLeituras.isVisible = conectado || sessao.injecao.resumo() != null
                    }
                }
                launch { sessao.injecao.estado.collect(::mostrar) }
                launch { combine(viewModel.freezeFrame, app.preferenciasApp.config) { ff, _ -> ff }.collect(::mostrarFreezeFrame) }
            }
        }
    }

    override fun onStop() {
        app.sessaoObd.fecharInjecao(this)
        super.onStop()
    }

    private fun mostrar(estado: EstadoInjecao) = with(binding) {
        textoRegime.definirTexto(
            getString(
                R.string.injecao_regime,
                estado.regime?.titulo ?: getString(R.string.injecao_regime_nenhum),
                estado.agua?.let { app.preferenciasApp.config.value.conversor.temperatura(it).comoNumero(0) } ?: TextosInjecao.SEM_VALOR,
                app.preferenciasApp.config.value.conversor.simboloTemperatura,
            ),
        )
        val suporte = estado.suporte
        banco(textoBanco1, dicaBanco1, suporte.banco1, estado.banco1)
        banco(textoBanco2, dicaBanco2, suporte.banco2, estado.banco2)

        val temPre = suporte.sondaPre != null
        grupoSondas.isVisible = temPre
        textoSondasValores.definirTexto(
            if (!temPre) {
                getString(R.string.injecao_nao_suportado)
            } else {
                val unidadePre = if (suporte.tipoSondaPre == TipoSondaPre.ESTREITA) "V" else "λ"
                val pos = if (suporte.sondaPos == null) {
                    getString(R.string.injecao_nao_suportado)
                } else {
                    estado.sondaPos?.let { it.comoNumero(2) + " V" } ?: TextosInjecao.SEM_VALOR
                }
                getString(
                    R.string.injecao_sondas_valores,
                    suporte.sondaPre,
                    estado.sondaPre?.let { "${it.comoNumero(2)} $unidadePre" } ?: TextosInjecao.SEM_VALOR,
                    pos,
                )
            },
        )
        if (temPre) {
            textoSondasDiagnostico.definirTexto(estado.sondas.texto)
            val gravidade = listOfNotNull(estado.sondas.pre?.gravidade, estado.sondas.catalisador?.gravidade).maxOrNull()
            textoSondasDiagnostico.setTextColor(cor(gravidade))
            mostrarCurva(estado)
        }
    }

    private fun banco(valores: TextView, dica: TextView, suporte: SuporteBanco, leitura: LeituraBanco) {
        dica.isVisible = suporte.suportado
        if (!suporte.suportado) {
            valores.definirTexto(getString(R.string.injecao_nao_suportado))
            valores.setTextColor(cor(null))
            return
        }
        valores.definirTexto(
            getString(
                R.string.injecao_banco_valores,
                TextosInjecao.trim(leitura.total),
                if (suporte.stft == null) getString(R.string.injecao_nao_suportado) else TextosInjecao.trim(leitura.stft),
                if (suporte.ltft == null) getString(R.string.injecao_nao_suportado) else TextosInjecao.trim(leitura.ltft),
                TextosInjecao.media(leitura.lenta, leitura.amostrasLenta),
                TextosInjecao.media(leitura.carga, leitura.amostrasCarga),
            ),
        )
        valores.setTextColor(cor(leitura.total?.let { com.charruamotors.oficina.injecao.AnaliseMistura.classificar(it).gravidade }))
        dica.definirTexto(leitura.dica)
    }

    private fun mostrarCurva(estado: EstadoInjecao) {
        val curva = estado.curva
        if (curva.lastOrNull() == ultimoPonto) return
        ultimoPonto = curva.lastOrNull()
        val instantes = LongArray(curva.size) { curva[it].instanteMs }
        val estreita = estado.suporte.tipoSondaPre == TipoSondaPre.ESTREITA
        val series = buildList {
            add(
                GraficoSeriesView.Serie(
                    getString(R.string.injecao_serie_s1),
                    DoubleArray(curva.size) { curva[it].pre ?: Double.NaN },
                    GraficoSeriesView.CORES_AO_VIVO[0],
                    if (estreita) ESCALA_TENSAO else ESCALA_LAMBDA,
                ),
            )
            if (estado.suporte.sondaPos != null) {
                add(
                    GraficoSeriesView.Serie(
                        getString(R.string.injecao_serie_s2),
                        DoubleArray(curva.size) { curva[it].pos ?: Double.NaN },
                        GraficoSeriesView.CORES_AO_VIVO[1],
                        ESCALA_TENSAO,
                    ),
                )
            }
        }
        val inicio = instantes.firstOrNull() ?: 0L
        val fim = instantes.lastOrNull() ?: 0L
        binding.graficoSondas.mostrarAoVivo(instantes, series, JanelaReplay(inicio, maxOf(fim, inicio + JANELA_MINIMA_MS)))
    }

    private fun mostrarFreezeFrame(estado: EstadoFreezeFrame) = with(binding) {
        botaoRecarregarFf.isEnabled = estado != EstadoFreezeFrame.Carregando && estado != EstadoFreezeFrame.SemConexao
        val dados = estado as? EstadoFreezeFrame.Dados
        textoFfDtc.isVisible = dados != null
        textoFfValores.isVisible = dados != null
        textoFfEstado.isVisible = dados == null
        if (dados != null) {
            textoFfDtc.definirTexto(
                listOfNotNull(dados.quadro.dtc ?: getString(R.string.obd_ff_sem_dtc), dados.descricao).joinToString(" · "),
            )
            textoFfValores.definirTexto(
                TextosInjecao.linhasFreezeFrame(dados.quadro, app.preferenciasApp.config.value.conversor)
                    .joinToString("\n") { (rotulo, valor) -> "$rotulo: $valor" },
            )
            return@with
        }
        textoFfEstado.definirTexto(
            when (estado) {
                EstadoFreezeFrame.SemConexao -> getString(R.string.oficina_requer_conexao_injecao)
                EstadoFreezeFrame.Carregando -> getString(R.string.injecao_ff_lendo)
                EstadoFreezeFrame.NaoSuportado -> getString(R.string.injecao_nao_suportado)
                EstadoFreezeFrame.Vazio -> getString(R.string.injecao_ff_vazio)
                is EstadoFreezeFrame.Erro -> getString(R.string.injecao_ff_erro, estado.mensagem)
                is EstadoFreezeFrame.Dados -> ""
            },
        )
    }

    private fun cor(gravidade: Int?): Int = ContextCompat.getColor(
        this,
        when (gravidade) {
            null -> R.color.texto_secundario
            0 -> R.color.veredito_bom
            1 -> R.color.veredito_atencao
            else -> R.color.veredito_ruim
        },
    )

    companion object {
        private const val EXTRA_FREEZE_FRAME = "freeze_frame"
        private const val JANELA_MINIMA_MS = 20_000L
        private val ESCALA_TENSAO = 0.0 to 1.1
        private val ESCALA_LAMBDA = 0.7 to 1.3

        fun intent(context: Context, freezeFrame: Boolean = false): Intent =
            Intent(context, InjecaoActivity::class.java).putExtra(EXTRA_FREEZE_FRAME, freezeFrame)
    }
}
