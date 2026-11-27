package com.charruamotors.ui.diagnostico.historico

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.FragmentHistoricoBinding
import com.charruamotors.obd.aprendizado.ResultadoExclusao
import com.charruamotors.obd.log.DestinoLogs
import com.charruamotors.obd.sessao.ViagemSessao
import com.charruamotors.ui.desempenho.EstatisticasViagemActivity
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.diagnostico.abastecimentos.AbastecimentosFragment
import com.charruamotors.ui.diagnostico.obd.LeituraObdAdapter
import com.charruamotors.ui.laudo.LaudoUi
import com.charruamotors.ui.oficina.replay.ArquivoLogCsv
import com.charruamotors.ui.oficina.replay.ReplayLogActivity
import com.charruamotors.ui.oficina.replay.TextoLog
import com.charruamotors.ui.util.ArquivoExportado
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.TextoCompartilhavel
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Aba Histórico, o único ponto de entrada para o que já foi rodado: os **abastecimentos** (consumo
 * médio e custo por km), as **sessões** gravadas (um cartão por sessão, com replay gráfico, log
 * técnico e laudo PDF) e as **viagens** (a viagem em curso ou a última e as leituras salvas no Painel).
 */
class HistoricoFragment : Fragment() {

    private var _binding: FragmentHistoricoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HistoricoViewModel by viewModels { HistoricoViewModel.Factory }
    private val adapterSessoes = SessaoLogAdapter(
        aoReplay = { startActivity(ReplayLogActivity.intent(requireContext(), it.uri, it.nome)) },
        aoLogTecnico = { viewModel.abrirLogTecnico(it) },
        aoLaudo = ::pedirLaudo,
        aoMaisAcoes = ::maisAcoes,
    )
    private val adapterLeituras = LeituraObdAdapter()

    private val abrirDocumento = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startActivity(ReplayLogActivity.intentExterno(requireContext(), it)) }
    }

    private val app: CharruaMotorsApp get() = requireActivity().application as CharruaMotorsApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoricoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.container_abastecimentos) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.container_abastecimentos, AbastecimentosFragment.novo(requireArguments().getLong(DiagnosticoActivity.EXTRA_VEICULO_ID)))
                .commit()
        }
        with(binding) {
            listaSessoes.adapter = adapterSessoes
            listaLeituras.adapter = adapterLeituras
            botaoAbrirCsv.setOnClickListener {
                runCatching { abrirDocumento.launch(TIPOS_CSV) }.onFailure { mensagem(it.message.orEmpty()) }
            }
            botaoAtualizar.setOnClickListener { viewModel.atualizar() }
            botaoLimparLogs.setOnClickListener { limparLogs() }
            botaoViagemDetalhes.setOnClickListener { startActivity(EstatisticasViagemActivity.intent(requireContext())) }
            botaoVerSessoes.setOnClickListener { grupoHistorico.check(R.id.botao_historico_sessoes) }
            grupoHistorico.addOnButtonCheckedListener { _, id, marcado -> if (marcado) mostrarSecao(id) }
            val inicial = savedInstanceState?.getInt(ESTADO_SECAO) ?: R.id.botao_historico_abastecimentos
            grupoHistorico.check(inicial)
            mostrarSecao(inicial)
        }
        // "Ver logs" de outra aba.
        parentFragmentManager.setFragmentResultListener(PEDIDO_SESSOES, viewLifecycleOwner) { _, _ ->
            binding.grupoHistorico.check(R.id.botao_historico_sessoes)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.sessoes.collect {
                        adapterSessoes.definirSessoes(it)
                        binding.textoSessoesVazio.isVisible = it.isEmpty()
                        binding.tituloSessoes.definirTexto(getString(R.string.historico_sessoes_titulo_qtd, it.size))
                    }
                }
                launch { viewModel.carregando.collect { binding.progressoSessoes.isInvisible = !it } }
                launch {
                    viewModel.eventos.collect { evento ->
                        val activity = requireActivity() as AppCompatActivity
                        when (evento) {
                            is EventoHistorico.LogTecnico -> mostrarLogTecnico(evento.arquivo, evento.texto)
                            is EventoHistorico.Laudo -> LaudoUi.mostrar(activity, binding.root, evento.resultado)
                            is EventoHistorico.ErroLeitura -> mensagem(getString(R.string.historico_erro_ler, evento.detalhe))
                        }
                    }
                }
                launch {
                    mostrarViagem(app.sessaoObd.viagem.value)
                    @OptIn(kotlinx.coroutines.FlowPreview::class)
                    app.sessaoObd.viagem.sample(INTERVALO_VIAGEM_MS).collect(::mostrarViagem)
                }
                launch {
                    viewModel.leituras.collect {
                        adapterLeituras.submitList(it)
                        binding.textoLeiturasVazio.isVisible = it.isEmpty()
                    }
                }
                launch { app.preferenciasApp.config.collect { adapterLeituras.definirConversor(it.conversor) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Volta do replay ou de uma sessão automática encerrada: pode haver log novo.
        viewModel.atualizar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putInt(ESTADO_SECAO, it.grupoHistorico.checkedButtonId) }
    }

    private fun mostrarSecao(id: Int) = with(binding) {
        containerAbastecimentos.isVisible = id == R.id.botao_historico_abastecimentos
        painelSessoes.isVisible = id == R.id.botao_historico_sessoes
        painelViagens.isVisible = id == R.id.botao_historico_viagens
    }

    /** Resumo da viagem em curso (ou da última); o detalhe completo fica em Estatísticas da Viagem. */
    private fun mostrarViagem(viagem: ViagemSessao) {
        val b = _binding ?: return
        val e = viagem.estatisticas
        val vazia = e.segundosTotais <= 0 && viagem.veiculo == null && !app.sessaoObd.estadoSessao.value.ativa
        val texto = if (vazia) {
            getString(R.string.viagem_vazia)
        } else {
            listOfNotNull(
                viagem.veiculo?.let { getString(R.string.viagem_veiculo, it) } ?: getString(R.string.viagem_sem_veiculo),
                getString(R.string.viagem_simulada).takeIf { viagem.simulada },
                listOfNotNull(
                    "${e.distanciaKm.comoNumero(2)} km",
                    "${(e.segundosTotais / 60).toLong()} min",
                    e.velocidadeMediaKmh?.let { getString(R.string.historico_viagem_media, it.roundToInt()) },
                    e.kmPorLitro?.let { "${it.comoNumero(1)} km/L" },
                ).joinToString(" · "),
            ).joinToString("\n")
        }
        b.textoViagemAtual.definirTexto(texto)
    }

    private fun mostrarLogTecnico(arquivo: ArquivoLogCsv, texto: TextoLog) {
        val contexto = requireContext()
        val conteudo = if (texto.truncado) {
            val lido = android.text.format.Formatter.formatShortFileSize(contexto, texto.conteudo.length.toLong())
            texto.conteudo + getString(R.string.replay_txt_truncado, lido)
        } else {
            texto.conteudo
        }
        TextoCompartilhavel.mostrarDialogo(contexto, arquivo.nome, conteudo) { compartilhar(arquivo) }
    }

    private fun pedirLaudo(sessao: SessaoSalva) =
        LaudoUi.pedirObservacoes(requireActivity() as AppCompatActivity) { viewModel.gerarLaudo(sessao, it) }

    /** Compartilhar cada arquivo da sessão ou apagá-la. */
    private fun maisAcoes(sessao: SessaoSalva) {
        val acoes = buildList<Pair<String, () -> Unit>> {
            sessao.csv?.let { csv -> add(getString(R.string.historico_acao_compartilhar_csv) to { compartilhar(csv) }) }
            sessao.txt?.let { txt -> add(getString(R.string.historico_acao_compartilhar_txt) to { compartilhar(txt) }) }
            add(getString(R.string.historico_acao_apagar_sessao) to { confirmarApagar(sessao) })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(sessao.titulo)
            .setItems(acoes.map { it.first }.toTypedArray()) { _, qual -> acoes[qual].second() }
            .show()
    }

    private fun confirmarApagar(sessao: SessaoSalva) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.historico_apagar_titulo)
            .setMessage(getString(R.string.historico_apagar_mensagem, sessao.arquivos.joinToString("\n") { it.nome }))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.historico_limpar_confirmar) { _, _ -> viewModel.apagar(sessao, ::avisarExclusao) }
            .show()
    }

    /** "Limpar logs": escolhe entre os do veículo atual e todos, com confirmação antes de apagar. */
    private fun limparLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (doVeiculo, todos) = viewModel.contagensParaLimpar()
            if (todos == 0) {
                mensagem(getString(R.string.historico_limpar_vazio))
                return@launch
            }
            var escolhido = 0
            val opcoes = arrayOf(getString(R.string.historico_limpar_veiculo, doVeiculo), getString(R.string.historico_limpar_todos, todos))
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.historico_limpar_titulo)
                .setSingleChoiceItems(opcoes, escolhido) { _, qual -> escolhido = qual }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.historico_limpar_confirmar) { _, _ -> viewModel.limpar(todos = escolhido == 1, ::avisarExclusao) }
                .show()
        }
    }

    private fun avisarExclusao(r: ResultadoExclusao) {
        val texto = if (r.falhas.isEmpty()) {
            getString(R.string.historico_apagado_ok, r.apagados.size)
        } else {
            getString(R.string.historico_apagado_falhas, r.apagados.size, r.falhas.size)
        }
        mensagem(texto)
    }

    private fun compartilhar(arquivo: ArquivoLogCsv) {
        val mime = if (arquivo.texto) DestinoLogs.MIME_TXT else DestinoLogs.MIME_CSV
        val exportado = ArquivoExportado(viewModel.uriCompartilhavel(arquivo), arquivo.nome, "")
        runCatching { startActivity(ExportadorCsv.intentCompartilhar(exportado, getString(R.string.replay_compartilhar_titulo), mime)) }
            .onFailure { mensagem(it.message.orEmpty()) }
    }

    private fun mensagem(texto: String) {
        val b = _binding ?: return
        if (texto.isNotBlank()) Snackbar.make(b.root, texto, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ESTADO_SECAO = "secao_historico"
        private const val PEDIDO_SESSOES = "historico_mostrar_sessoes"
        private const val INTERVALO_VIAGEM_MS = 1_000L

        /** Alguns gerenciadores de arquivos marcam o CSV como texto genérico ou binário. */
        private val TIPOS_CSV = arrayOf(
            "text/csv", "text/comma-separated-values", "application/csv", "text/plain",
            "application/vnd.ms-excel", "application/octet-stream",
        )

        /** Pede que a aba Histórico, ao aparecer, mostre as sessões gravadas. [gerenciador]: o das abas. */
        fun pedirSessoes(gerenciador: FragmentManager) = gerenciador.setFragmentResult(PEDIDO_SESSOES, Bundle.EMPTY)

        fun novo(veiculoId: Long) = HistoricoFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
