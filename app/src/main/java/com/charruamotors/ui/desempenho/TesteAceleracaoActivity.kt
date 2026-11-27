package com.charruamotors.ui.desempenho

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityTesteAceleracaoBinding
import com.charruamotors.databinding.ItemChipFiltroBinding
import com.charruamotors.direcao.desempenho.EstadoTeste
import com.charruamotors.direcao.desempenho.FaseTeste
import com.charruamotors.direcao.desempenho.ModalidadeTeste
import com.charruamotors.direcao.desempenho.MotivoCancelamento
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.pid.Pids
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * v2.6.0: Oficina → Testes de Aceleração. O cronômetro e o polling prioritário são da sessão OBD
 * ([com.charruamotors.obd.sessao.SessaoObd.testeAceleracao]); a tela escolhe a modalidade, mostra o
 * estado e os melhores tempos do carro. Sair da tela cancela o teste. A rotação não recria a
 * Activity (ver o manifesto), então girar o celular não cancela.
 */
class TesteAceleracaoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTesteAceleracaoBinding
    private val app: CharruaMotorsApp get() = application as CharruaMotorsApp
    private val teste get() = app.sessaoObd.testeAceleracao

    private val chips = mutableMapOf<Int, ModalidadeTeste>()
    private val formatoData = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")

    /** Veículo da sessão ou, sem sessão, o carro ativo: o dono do histórico. */
    private val veiculoId: Long
        get() = app.sessaoObd.estadoSessao.value.veiculoId.takeIf { it > 0 } ?: app.perfisCarro.veiculoAtivo.value

    private var nomeVeiculo: Pair<Long, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityTesteAceleracaoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Um resultado de outra visita (talvez de outro carro) não fica na tela.
        teste.selecionar(teste.estado.value.modalidade)
        for (modalidade in ModalidadeTeste.entries) {
            val chip = ItemChipFiltroBinding.inflate(layoutInflater, binding.grupoModalidades, false).root
            chip.id = View.generateViewId()
            chip.text = modalidade.titulo
            chips[chip.id] = modalidade
            binding.grupoModalidades.addView(chip)
        }
        chips.entries.first { it.value == teste.estado.value.modalidade }.let { binding.grupoModalidades.check(it.key) }
        binding.grupoModalidades.setOnCheckedStateChangeListener { _, marcados ->
            val modalidade = marcados.firstOrNull()?.let(chips::get) ?: return@setOnCheckedStateChangeListener
            teste.selecionar(modalidade)
            mostrarHistorico(modalidade)
        }
        binding.botaoTeste.setOnClickListener { alternar() }
        binding.botaoApagar.setOnClickListener { confirmarExclusao() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(teste.estado, app.sessaoObd.dados) { e, dados -> e to dados?.get(Pids.RPM) }
                        .collect { (e, rpm) -> mostrar(e, rpm) }
                }
                launch {
                    combine(app.sessaoObd.estadoSessao, app.perfisCarro.alteracoes, app.sessaoObd.infoConexao) { _, _, info -> info }
                        .collect { info ->
                            binding.textoPolling.setText(
                                when {
                                    app.sessaoObd.modo.value == ModoObd.SIMULADO -> R.string.aceleracao_nao_salvo
                                    veiculoId <= 0 -> R.string.aceleracao_sem_veiculo
                                    info.modo != ModoComunicacaoEcu.OBD2_PADRAO -> R.string.aceleracao_polling_normal
                                    else -> R.string.aceleracao_polling_focado
                                },
                            )
                            carregarNome()
                            mostrarHistorico(teste.estado.value.modalidade)
                        }
                }
            }
        }
    }

    override fun onStop() {
        // Saiu da tela (ou apagou o celular): o teste acaba e o polling volta ao normal.
        teste.cancelar(MotivoCancelamento.SAIU_DA_TELA)
        super.onStop()
    }

    private fun alternar() {
        if (teste.estado.value.ativo) {
            teste.cancelar()
            return
        }
        val suportados = app.sessaoObd.pidsSuportados.value
        if (suportados.isNotEmpty() && Pids.VELOCIDADE !in suportados) {
            Snackbar.make(binding.root, R.string.aceleracao_sem_velocidade, Snackbar.LENGTH_LONG).show()
            return
        }
        val modalidade = teste.estado.value.modalidade
        if (!app.sessaoObd.armarTeste(modalidade)) {
            Snackbar.make(binding.root, R.string.aceleracao_requer_conexao, Snackbar.LENGTH_LONG).show()
            return
        }
        // Demonstração: o carro simulado para e arranca até além da maior chegada.
        (app.sessaoObd.cliente as? ObdClientFake)?.simularArrancada(VELOCIDADE_FINAL_DEMONSTRACAO)
    }

    private fun mostrar(e: EstadoTeste, rpm: Double?) = with(binding) {
        val m = e.modalidade
        textoTempo.definirTexto(getString(R.string.aceleracao_tempo, segundos(e.resultado?.tempoMs ?: e.decorridoMs)))
        textoEstado.definirTexto(
            when (e.fase) {
                FaseTeste.DESARMADO -> getString(R.string.aceleracao_estado_desarmado)
                FaseTeste.AGUARDANDO_PARADA ->
                    if (m.partidaParada) getString(R.string.aceleracao_estado_parada)
                    else getString(R.string.aceleracao_estado_abaixo, m.inicioKmh.toInt())
                FaseTeste.ARMADO ->
                    if (m.partidaParada) getString(R.string.aceleracao_estado_armado)
                    else getString(R.string.aceleracao_estado_armado_retomada, m.inicioKmh.toInt())
                FaseTeste.EM_MEDICAO -> getString(R.string.aceleracao_estado_medindo)
                FaseTeste.CONCLUIDO -> getString(R.string.aceleracao_estado_concluido, e.resultado?.velocidadeFinalKmh?.roundToInt() ?: 0)
                FaseTeste.CANCELADO -> e.motivo?.texto.orEmpty()
            },
        )
        textoLeituras.isVisible = e.ativo || e.fase == FaseTeste.CONCLUIDO
        textoLeituras.definirTexto(
            getString(
                R.string.aceleracao_leituras,
                e.velocidadeKmh?.roundToInt()?.toString() ?: getString(R.string.valor_indisponivel),
                rpm?.roundToInt()?.toString() ?: getString(R.string.valor_indisponivel),
                e.distanciaM.roundToInt(),
            ),
        )
        botaoTeste.setText(
            when {
                e.ativo -> R.string.aceleracao_cancelar
                e.fase == FaseTeste.DESARMADO -> R.string.aceleracao_armar
                else -> R.string.aceleracao_armar_novamente
            },
        )
        for (i in 0 until grupoModalidades.childCount) grupoModalidades.getChildAt(i).isEnabled = !e.ativo
    }

    private fun carregarNome() {
        val id = veiculoId
        if (id <= 0 || nomeVeiculo?.first == id) return
        lifecycleScope.launch {
            val nome = app.veiculoRepository.buscarPorId(id)?.descricao ?: return@launch
            nomeVeiculo = id to nome
            mostrarHistorico(teste.estado.value.modalidade)
        }
    }

    private fun mostrarHistorico(modalidade: ModalidadeTeste) {
        val id = veiculoId
        val marcas = if (id > 0) app.perfisCarro.ler(id).historicoDesempenho.melhores(modalidade) else emptyList()
        val nome = nomeVeiculo?.takeIf { it.first == id }?.second ?: getString(R.string.valor_indisponivel)
        binding.textoHistoricoTitulo.definirTexto(getString(R.string.aceleracao_historico, modalidade.titulo, nome))
        binding.textoHistorico.definirTexto(
            if (marcas.isEmpty()) {
                getString(R.string.aceleracao_historico_vazio)
            } else {
                marcas.mapIndexed { i, marca ->
                    getString(
                        R.string.aceleracao_historico_linha,
                        i + 1,
                        segundos(marca.tempoMs),
                        marca.velocidadeFinalKmh.roundToInt(),
                        formatoData.format(Instant.ofEpochMilli(marca.dataMs).atZone(ZoneId.systemDefault())),
                    )
                }.joinToString("\n")
            },
        )
        binding.botaoApagar.isVisible = marcas.isNotEmpty()
    }

    private fun confirmarExclusao() {
        val id = veiculoId
        val modalidade = teste.estado.value.modalidade
        if (id <= 0) return
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.aceleracao_apagar_confirmar, modalidade.titulo))
            .setPositiveButton(R.string.aceleracao_apagar_botao) { _, _ ->
                app.perfisCarro.atualizar(id) { it.copy(historicoDesempenho = it.historicoDesempenho.sem(modalidade)) }
                mostrarHistorico(modalidade)
            }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    private fun segundos(ms: Long): String = (ms / 1000.0).comoNumero(2)

    companion object {
        private const val VELOCIDADE_FINAL_DEMONSTRACAO = 165.0

        fun intent(context: Context): Intent = Intent(context, TesteAceleracaoActivity::class.java)
    }
}
