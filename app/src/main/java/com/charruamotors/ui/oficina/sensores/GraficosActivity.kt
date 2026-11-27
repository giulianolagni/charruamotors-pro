package com.charruamotors.ui.oficina.sensores

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityGraficosBinding
import com.charruamotors.databinding.ItemChipFiltroBinding
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.oficina.sensores.ControleGrafico
import com.charruamotors.oficina.sensores.FormatadorSensor
import com.charruamotors.oficina.sensores.JanelaTempo
import com.charruamotors.oficina.sensores.PresetGrafico
import com.charruamotors.oficina.sensores.PresetsGrafico
import com.charruamotors.oficina.sensores.SelecaoGrafico
import com.charruamotors.ui.componentes.GraficoSeriesView
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sobrevive à rotação: a pausa, o "Zerar", a janela e a seleção sem veículo ativo. */
class GraficosViewModel : ViewModel() {
    val controle = ControleGrafico()
    var selecaoSemVeiculo: List<String> = emptyList()
}

/**
 * Oficina → Gráficos em Tempo Real: 1 a 4 sensores da sessão em [GraficoSeriesView], com janela de
 * 30 s, 2 min ou 5 min, Pausar, Zerar, cursor de toque (horário e valores) e presets. A seleção e os
 * presets ficam no perfil do veículo ativo. Enquanto está na tela, o polling prioriza os sensores
 * desenhados (v2.5.0).
 */
class GraficosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGraficosBinding
    private val app: CharruaMotorsApp get() = application as CharruaMotorsApp
    private val viewModel: GraficosViewModel by viewModels()
    private val veiculoAtivo: Long get() = app.perfisCarro.veiculoAtivo.value

    /** Combustível do veículo ativo (decide as sugestões); null = ainda não lido ou sem veículo. */
    private var diesel: Boolean? = null

    /** Sensores desenhados na última passada (rótulos de escala). */
    private var desenhados: List<DefinicaoPid> = emptyList()

    /** Presets desenhados nos chips, para só remontá-los quando mudarem. */
    private var presetsNaTela: Pair<List<PresetGrafico>, List<PresetGrafico>>? = null

    private val relogio = DateTimeFormatter.ofPattern("HH:mm:ss.S")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityGraficosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.subtitle = app.preferenciasAutoConexao.config.value.veiculoDescricao

        val controle = viewModel.controle
        binding.grupoJanela.check(
            when (controle.janela) {
                JanelaTempo.S30 -> R.id.botao_30s
                JanelaTempo.M2 -> R.id.botao_2min
                JanelaTempo.M5 -> R.id.botao_5min
            },
        )
        binding.grupoJanela.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            controle.janela = when (id) {
                R.id.botao_30s -> JanelaTempo.S30
                R.id.botao_5min -> JanelaTempo.M5
                else -> JanelaTempo.M2
            }
            atualizar()
        }
        binding.botaoPausar.setOnClickListener {
            controle.alternarPausa(app.sessaoObd.historico.amostras())
            atualizar()
        }
        binding.botaoZerar.setOnClickListener {
            controle.zerar(app.sessaoObd.historico.amostras())
            atualizar()
        }
        binding.botaoSensores.setOnClickListener { escolherSensores() }
        binding.grafico.aoMoverCursorAoVivo = ::atualizar
        binding.grafico.formatarCursor = ::horario
        binding.grafico.formatarValor = { i, v ->
            desenhados.getOrNull(i)?.let { FormatadorSensor.valor(it, v, app.preferenciasApp.config.value.conversor) }.orEmpty()
        }

        lifecycleScope.launch {
            if (veiculoAtivo > 0) {
                diesel = app.veiculoRepository.buscarPorId(veiculoAtivo)?.let { it.tipoCombustivel == TipoCombustivel.DIESEL }
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        atualizar()
                        delay(INTERVALO_TELA_MS)
                    }
                } finally {
                    // Saiu da tela: o polling volta ao normal.
                    app.sessaoObd.liberarSensoresVisiveis(this@GraficosActivity)
                }
            }
        }
    }

    private fun horario(tMs: Long): String = relogio.format(Instant.ofEpochMilli(tMs).atZone(ZoneId.systemDefault()))

    /** Sensores que a conexão suporta; um que ainda não respondeu passa a ser priorizado ao ser escolhido. */
    private fun disponiveis(): List<DefinicaoPid> = app.definicoesDaSessao()

    private fun salvos(): List<String> =
        if (veiculoAtivo > 0) app.perfisCarro.ler(veiculoAtivo).favoritosGraficos else viewModel.selecaoSemVeiculo

    private fun selecionados(disponiveis: List<DefinicaoPid>): List<DefinicaoPid> {
        val porComando = disponiveis.associateBy { it.comando }
        return SelecaoGrafico.efetiva(salvos(), disponiveis.map { it.comando }, diesel).mapNotNull { porComando[it] }
    }

    private fun atualizar() {
        val controle = viewModel.controle
        val disponiveis = disponiveis()
        val definicoes = selecionados(disponiveis)
        desenhados = definicoes
        val comandos = definicoes.map { it.comando }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) app.sessaoObd.definirSensoresVisiveis(this, comandos)
        mostrarPresets(disponiveis)

        val conversor = app.preferenciasApp.config.value.conversor
        val quadro = controle.quadro(if (controle.pausado) emptyList() else app.sessaoObd.historico.amostras(), comandos)
        binding.botaoPausar.setText(if (controle.pausado) R.string.graficos_retomar else R.string.graficos_pausar)
        binding.grafico.manterCursor = controle.pausado
        val fim = quadro.janela.fimMs
        binding.grafico.formatarTempo = { t ->
            val segundos = ((fim - t) / 1000.0).toInt()
            if (segundos <= 0) getString(R.string.graficos_agora) else "-" + duracao(segundos)
        }
        binding.grafico.mostrarAoVivo(
            quadro.instantes,
            definicoes.mapIndexed { i, d -> GraficoSeriesView.Serie(d.nome, quadro.series[i], GraficoSeriesView.CORES_AO_VIVO[i]) },
            quadro.janela,
        )

        val leitura = binding.grafico.indiceCursor?.let { quadro.leitura(it) }
        binding.textoCursor.definirTexto(
            when {
                definicoes.isEmpty() || quadro.vazio -> getString(R.string.graficos_sem_dados)
                leitura != null -> getString(R.string.graficos_cursor, horario(leitura.tMs), ((fim - leitura.tMs) / 1000.0).comoNumero(1))
                else -> getString(R.string.graficos_dica_cursor)
            },
        )
        listOf(binding.legenda1, binding.legenda2, binding.legenda3, binding.legenda4).forEachIndexed { i, view ->
            val d = definicoes.getOrNull(i)
            view.isVisible = d != null
            if (d == null) return@forEachIndexed
            val unidade = FormatadorSensor.unidade(d, conversor)
            fun texto(valor: Double?) = FormatadorSensor.valor(d, valor, conversor)
            view.setTextColor(GraficoSeriesView.CORES_AO_VIVO[i])
            view.definirTexto(
                if (leitura != null) {
                    getString(R.string.graficos_legenda_cursor, d.nome, texto(leitura.valores[d.comando]), unidade)
                } else {
                    val estatistica = quadro.estatistica(i)
                    getString(
                        R.string.graficos_legenda, d.nome, texto(quadro.atual(i)), unidade,
                        texto(estatistica?.minimo), texto(estatistica?.maximo),
                    )
                },
            )
        }
    }

    private fun duracao(segundos: Int): String =
        if (segundos < 60) "$segundos s" else "%d:%02d".format(segundos / 60, segundos % 60)

    // ------------------------------------------------------------------ presets

    private fun mostrarPresets(disponiveis: List<DefinicaoPid>) {
        val prontos = PresetsGrafico.prontos(disponiveis.map { it.comando }, app.sessaoObd.historico.estatisticas().keys, diesel)
        val salvos = if (veiculoAtivo > 0) app.perfisCarro.ler(veiculoAtivo).presetsGraficos else emptyList()
        if (presetsNaTela == prontos to salvos) return
        presetsNaTela = prontos to salvos
        binding.grupoPresets.removeAllViews()
        for (preset in prontos) adicionarChip(preset.nome) { aplicar(preset) }
        for (preset in salvos) {
            adicionarChip(preset.nome) { aplicar(preset) }.setOnLongClickListener {
                confirmarExclusao(preset)
                true
            }
        }
        adicionarChip(getString(R.string.graficos_salvar_preset)) { pedirNomeDoPreset() }
    }

    private fun adicionarChip(titulo: String, aoTocar: () -> Unit): Chip {
        val chip = ItemChipFiltroBinding.inflate(layoutInflater, binding.grupoPresets, false).root
        chip.text = titulo
        chip.isCheckable = false
        chip.setOnClickListener { aoTocar() }
        binding.grupoPresets.addView(chip)
        return chip
    }

    private fun aplicar(preset: PresetGrafico) {
        val suportados = disponiveis().map { it.comando }.toSet()
        val comandos = preset.comandos.filter { it in suportados }
        if (comandos.isEmpty()) {
            Toast.makeText(this, R.string.graficos_preset_indisponivel, Toast.LENGTH_SHORT).show()
            return
        }
        salvar(comandos)
    }

    private fun pedirNomeDoPreset() {
        val id = veiculoAtivo
        if (id <= 0) {
            Toast.makeText(this, R.string.graficos_preset_sem_veiculo, Toast.LENGTH_SHORT).show()
            return
        }
        val comandos = selecionados(disponiveis()).map { it.comando }
        if (comandos.isEmpty()) {
            Toast.makeText(this, R.string.graficos_sem_dados, Toast.LENGTH_SHORT).show()
            return
        }
        val campo = EditText(this).apply {
            hint = getString(R.string.graficos_nome_preset)
            filters = arrayOf(InputFilter.LengthFilter(PresetsGrafico.MAX_NOME))
            isSingleLine = true
            setText(getString(R.string.graficos_nome_padrao, app.perfisCarro.ler(id).presetsGraficos.size + 1))
            selectAll()
        }
        val margem = resources.getDimensionPixelSize(R.dimen.margem_tela)
        val caixa = FrameLayout(this).apply {
            setPadding(margem, 0, margem, 0)
            addView(campo)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.graficos_nome_preset)
            .setView(caixa)
            .setPositiveButton(R.string.graficos_salvar) { _, _ -> guardarPreset(id, PresetGrafico(campo.text.toString(), comandos)) }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    private fun guardarPreset(veiculoId: Long, preset: PresetGrafico) {
        var recusado = false
        app.perfisCarro.atualizar(veiculoId) { perfil ->
            val novos = PresetsGrafico.salvar(perfil.presetsGraficos, preset)
            recusado = novos == null
            if (novos == null) perfil else perfil.copy(presetsGraficos = novos)
        }
        val mensagem = if (recusado) {
            getString(R.string.graficos_preset_recusado, PresetsGrafico.MAX_SALVOS)
        } else {
            getString(R.string.graficos_preset_salvo)
        }
        Toast.makeText(this, mensagem, Toast.LENGTH_SHORT).show()
        atualizar()
    }

    private fun confirmarExclusao(preset: PresetGrafico) {
        val id = veiculoAtivo
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.graficos_apagar_preset, preset.nome))
            .setPositiveButton(R.string.graficos_apagar) { _, _ ->
                app.perfisCarro.atualizar(id) { it.copy(presetsGraficos = it.presetsGraficos - preset) }
                atualizar()
            }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    // ------------------------------------------------------------------ sensores

    private fun escolherSensores() {
        val disponiveis = disponiveis()
        if (disponiveis.isEmpty()) {
            Toast.makeText(this, R.string.graficos_sem_dados, Toast.LENGTH_SHORT).show()
            return
        }
        val escolhidos = selecionados(disponiveis).map { it.comando }.toMutableList()
        val nomes = disponiveis.map { "${it.nome} (${it.rotuloComando})" }.toTypedArray<CharSequence>()
        val marcados = BooleanArray(disponiveis.size) { disponiveis[it].comando in escolhidos }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.graficos_escolher, SelecaoGrafico.MAXIMO))
            .setMultiChoiceItems(nomes, marcados) { dialogo, i, marcado ->
                val comando = disponiveis[i].comando
                when {
                    !marcado -> escolhidos.remove(comando)
                    escolhidos.size < SelecaoGrafico.MAXIMO -> escolhidos.add(comando)
                    else -> {
                        marcados[i] = false
                        (dialogo as AlertDialog).listView.setItemChecked(i, false)
                        Toast.makeText(this, getString(R.string.graficos_limite, SelecaoGrafico.MAXIMO), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton(R.string.graficos_aplicar) { _, _ -> salvar(escolhidos.toList()) }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    /** Lista vazia = volta à sugestão. */
    private fun salvar(comandos: List<String>) {
        val id = veiculoAtivo
        if (id > 0) app.perfisCarro.atualizar(id) { it.copy(favoritosGraficos = comandos) } else viewModel.selecaoSemVeiculo = comandos
        atualizar()
    }

    companion object {
        private const val INTERVALO_TELA_MS = 200L

        fun intent(context: Context): Intent = Intent(context, GraficosActivity::class.java)
    }
}
