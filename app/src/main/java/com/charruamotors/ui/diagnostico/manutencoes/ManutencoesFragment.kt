package com.charruamotors.ui.diagnostico.manutencoes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import com.charruamotors.R
import com.charruamotors.databinding.FragmentManutencoesBinding
import com.charruamotors.domain.calculo.ProximaManutencao
import com.charruamotors.domain.manutencao.ItemManutencao
import com.charruamotors.domain.model.ManutencaoComPecas
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoTexto
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Aba 2 — Manutenções: histórico, previsão da próxima manutenção e CRUD. */
class ManutencoesFragment : Fragment() {

    private var _binding: FragmentManutencoesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ManutencoesViewModel by viewModels { ManutencoesViewModel.Factory }

    private val adapterProximas = ProximasManutencoesAdapter()
    private val adapterLista = ManutencaoAdapter(
        aoClicar = { abrirFormulario(it.manutencao.id) },
        aoExcluir = ::confirmarExclusao,
    )

    private val adapterPlano = PlanoManutencaoAdapter(
        aoCorrigirOdometro = {
            viewModel.resumo.value?.let { DialogosPreventiva.corrigirOdometro(requireContext(), it.odometroKm, viewModel::corrigirOdometro) }
        },
        aoAdicionar = {
            DialogosPreventiva.escolherPreset(requireContext()) { preset ->
                viewModel.resumo.value?.let { editarItem(DialogosPreventiva.novoItem(preset, it.odometroKm)) }
            }
        },
        aoEditar = ::editarItem,
        aoRegistrarTroca = ::registrarTroca,
        aoVerHistorico = { viewModel.resumo.value?.let { DialogosPreventiva.historico(requireContext(), it.trocas) } },
    )

    private fun editarItem(item: ItemManutencao) = DialogosPreventiva.editarItem(
        requireContext(),
        childFragmentManager,
        item,
        aoSalvar = viewModel::salvarItem,
        aoExcluir = { viewModel.removerItem(item.id) },
    )

    private fun registrarTroca(item: ItemManutencao) {
        val km = viewModel.resumo.value?.odometroKm ?: return
        val pergunta = getString(R.string.preventiva_registrar_confirmar, item.nome, km.comoMilhar())
        DialogosPreventiva.confirmar(requireContext(), pergunta) {
            viewModel.registrarTroca(item.id)
            Snackbar.make(binding.root, R.string.preventiva_registrada, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentManutencoesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.listaManutencoes.adapter = ConcatAdapter(adapterPlano, adapterProximas, adapterLista)
        binding.botaoNovaManutencao.setOnClickListener { abrirFormulario(0L) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.resumo.collect { it?.let(::mostrarResumo) } }
                launch { viewModel.eventos.collect(::tratarEvento) }
            }
        }
    }

    private fun mostrarResumo(resumo: ResumoManutencoes) {
        adapterPlano.mostrar(resumo)
        adapterLista.submitList(resumo.itens)
        adapterProximas.texto = textoProximas(resumo.proximas)
    }

    override fun onResume() {
        super.onResume()
        // As datas andam: reavalia a situação mesmo sem nada ter mudado.
        viewModel.resumo.value?.let { viewModel.reavaliar() }
    }

    /** Uma linha por tipo com previsão (a mais urgente primeiro); null quando não há previsão. */
    private fun textoProximas(proximas: List<ProximaManutencao>): String? {
        if (proximas.isEmpty()) return null
        return proximas.take(MAX_PREVISOES).joinToString("\n") { it.comoTexto(requireContext()) }
    }

    private fun abrirFormulario(id: Long) {
        if (childFragmentManager.findFragmentByTag(ManutencaoSheet.TAG) == null) {
            ManutencaoSheet.novo(id).show(childFragmentManager, ManutencaoSheet.TAG)
        }
    }

    private fun confirmarExclusao(item: ManutencaoComPecas) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manutencao_excluir_titulo)
            .setMessage(R.string.manutencao_excluir_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.botao_excluir) { _, _ -> viewModel.excluir(item.manutencao) }
            .show()
    }

    private fun tratarEvento(evento: EventoManutencao) {
        val mensagem = when (evento) {
            EventoManutencao.Salva -> {
                (childFragmentManager.findFragmentByTag(ManutencaoSheet.TAG) as? DialogFragment)?.dismiss()
                R.string.manutencao_salva
            }
            EventoManutencao.Excluida -> R.string.manutencao_excluida
        }
        Snackbar.make(binding.root, mensagem, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val MAX_PREVISOES = 3

        fun novo(veiculoId: Long) = ManutencoesFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
