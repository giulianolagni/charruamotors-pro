package com.charruamotors.ui.oficina.sensores

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivitySensoresBinding
import com.charruamotors.databinding.ItemChipFiltroBinding
import com.charruamotors.direcao.painel.ResultadoFixar
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.oficina.sensores.CategoriaSensor
import com.charruamotors.oficina.sensores.LinhaSensor
import com.charruamotors.oficina.sensores.MontadorListaSensores
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sobrevive à rotação: o chip de categoria escolhido. */
class SensoresViewModel : ViewModel() {
    var categoria: CategoriaSensor? = null
    var soFixados = false
}

/**
 * Oficina → Todos os Sensores: tudo o que o carro da sessão suporta (real ou simulado), com valor ao
 * vivo, mínimo/máximo, busca, categorias e o "+ Painel" do veículo ativo. Enquanto está na tela, o
 * polling prioriza as linhas visíveis (v2.5.0, [com.charruamotors.obd.sessao.SessaoObd.definirSensoresVisiveis]).
 */
class SensoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySensoresBinding
    private val app: CharruaMotorsApp get() = application as CharruaMotorsApp
    private val viewModel: SensoresViewModel by viewModels()
    private val adaptador = SensorLinhaAdapter(::alternarNoPainel)
    private val gerenciadorLista by lazy { LinearLayoutManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivitySensoresBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.subtitle = app.preferenciasAutoConexao.config.value.veiculoDescricao

        binding.lista.layoutManager = gerenciadorLista
        binding.lista.adapter = adaptador
        binding.lista.itemAnimator = null
        // Rolou (ou a lista mudou de tamanho): o polling passa a priorizar o que ficou visível.
        binding.lista.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = priorizarVisiveis()
        })
        montarCategorias()
        binding.campoBusca.doAfterTextChanged { atualizar() }
        binding.grupoFiltro.addOnButtonCheckedListener { _, _, marcado -> if (marcado) atualizar() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        atualizar()
                        delay(INTERVALO_TELA_MS)
                    }
                } finally {
                    // Saiu da tela: o polling volta ao normal.
                    app.sessaoObd.liberarSensoresVisiveis(this@SensoresActivity)
                }
            }
        }
    }

    private fun montarCategorias() {
        fun chip(titulo: String, marcado: Boolean, aoEscolher: () -> Unit) {
            val chip = ItemChipFiltroBinding.inflate(layoutInflater, binding.grupoCategoria, false).root
            chip.id = android.view.View.generateViewId()
            chip.text = titulo
            binding.grupoCategoria.addView(chip)
            if (marcado) binding.grupoCategoria.check(chip.id)
            chip.setOnClickListener {
                aoEscolher()
                atualizar()
                binding.lista.scrollToPosition(0)
            }
        }
        val semFiltro = viewModel.categoria == null && !viewModel.soFixados
        chip(getString(R.string.sensores_categoria_todas), semFiltro) {
            viewModel.categoria = null
            viewModel.soFixados = false
        }
        chip(getString(R.string.sensores_categoria_fixados), viewModel.soFixados) {
            viewModel.categoria = null
            viewModel.soFixados = true
        }
        for (categoria in CategoriaSensor.entries) {
            chip(categoria.titulo, viewModel.categoria == categoria) {
                viewModel.categoria = categoria
                viewModel.soFixados = false
            }
        }
    }

    private fun atualizar() {
        val suportados = binding.grupoFiltro.checkedButtonId != R.id.botao_todos
        val conectado = app.sessaoObd.estado.value is EstadoConexaoObd.Conectado
        val linhas = MontadorListaSensores.montar(
            definicoes = if (suportados) app.definicoesDaSessao() else app.definicoesDoPerfil(),
            estatisticas = app.sessaoObd.historico.estatisticas(),
            atual = app.sessaoObd.dados.value?.valores?.takeIf { conectado }.orEmpty(),
            conversor = app.preferenciasApp.config.value.conversor,
            painel = app.preferenciasPainel.config.value,
            filtro = binding.campoBusca.text?.toString().orEmpty(),
            soAtivos = false,
            categoria = viewModel.categoria,
            soFixados = viewModel.soFixados,
        )
        // As linhas visíveis só mudam depois de o RecyclerView aplicar a lista nova.
        adaptador.submitList(linhas) { binding.lista.post(::priorizarVisiveis) }
        binding.textoResumo.text = getString(R.string.sensores_resumo, linhas.size, linhas.count { it.respondeu })
        binding.textoVazio.isVisible = linhas.isEmpty()
        binding.textoVazio.setText(if (conectado) R.string.sensores_vazio else R.string.oficina_requer_conexao)
        priorizarVisiveis()
    }

    private fun priorizarVisiveis() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val linhas = adaptador.currentList
        val primeira = gerenciadorLista.findFirstVisibleItemPosition()
        val ultima = gerenciadorLista.findLastVisibleItemPosition()
        // Lista ainda sem desenho: espera a próxima passada em vez de registrar "nada visível".
        if (linhas.isNotEmpty() && primeira < 0) return
        val visiveis = if (primeira < 0) emptyList() else linhas.subList(primeira.coerceAtMost(linhas.size), (ultima + 1).coerceIn(0, linhas.size))
        app.sessaoObd.definirSensoresVisiveis(this, visiveis.map { it.comando })
    }

    private fun alternarNoPainel(linha: LinhaSensor) {
        var resultado: ResultadoFixar? = null
        app.preferenciasPainel.atualizar { atual ->
            val (nova, feito) = atual.alternarNoPainel(linha.comando)
            resultado = feito
            nova
        }
        val mensagem = when (resultado ?: return) {
            ResultadoFixar.MOSTRADOR_ADICIONADO -> R.string.sensores_mostrador_adicionado
            ResultadoFixar.MOSTRADOR_REMOVIDO -> R.string.sensores_mostrador_removido
            ResultadoFixar.LISTA_ADICIONADO -> R.string.sensores_lista_adicionado
            ResultadoFixar.LISTA_REMOVIDO -> R.string.sensores_lista_removido
            ResultadoFixar.ULTIMO_MOSTRADOR -> R.string.sensores_ultimo_mostrador
        }
        Snackbar.make(binding.root, mensagem, Snackbar.LENGTH_SHORT).show()
        atualizar()
    }

    companion object {
        private const val INTERVALO_TELA_MS = 300L

        fun intent(context: Context): Intent = Intent(context, SensoresActivity::class.java)
    }
}
