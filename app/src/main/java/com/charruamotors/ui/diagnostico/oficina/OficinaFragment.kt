package com.charruamotors.ui.diagnostico.oficina

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.FragmentOficinaBinding
import com.charruamotors.databinding.ItemCardOficinaBinding
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.ui.desempenho.CalibracaoConsumoActivity
import com.charruamotors.ui.desempenho.TesteAceleracaoActivity
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.diagnostico.DiagnosticoPagerAdapter
import com.charruamotors.ui.oficina.atuadores.TesteAtuadoresActivity
import com.charruamotors.ui.oficina.bateria.TesteBateriaActivity
import com.charruamotors.ui.oficina.checkup.CheckupObdActivity
import com.charruamotors.ui.oficina.checkup.FerramentaCheckup
import com.charruamotors.ui.oficina.falhas.DiagnosticoErrosActivity
import com.charruamotors.ui.oficina.injecao.InjecaoActivity
import com.charruamotors.ui.oficina.sensores.GraficosActivity
import com.charruamotors.ui.oficina.sensores.SensoresActivity
import com.charruamotors.ui.oficina.terminal.TerminalObdActivity
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Aba Oficina: as ferramentas principais em cards grandes e ilustrados, em três seções —
 * **Diagnóstico** (DTCs, Checkup/Prontidão, Falhas de Ignição, Injeção, Bateria e Sensores),
 * **Testes e Performance** (Gráficos, Testes de Aceleração e Calibração de Consumo) e **Engenharia**
 * (Varredura de PIDs, Terminal ELM327 e Teste de Atuadores). Todas usam o scanner conectado no
 * Painel; bateria, varredura e terminal abrem uma sessão manual que pausa o polling até voltar.
 * Estatísticas de viagem ficam no Histórico e os bicos injetores no Diagnóstico de Injeção (v2.10.0).
 */
class OficinaFragment : Fragment() {

    private var _binding: FragmentOficinaBinding? = null
    private val binding get() = _binding!!

    private val app: CharruaMotorsApp get() = requireActivity().application as CharruaMotorsApp
    private val veiculoId: Long get() = requireArguments().getLong(DiagnosticoActivity.EXTRA_VEICULO_ID)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOficinaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        with(binding) {
            preencher(cardSensores, R.string.oficina_card_sensores, R.string.oficina_card_sensores_desc, R.drawable.ic_checklist, R.color.azul_primario)
            preencher(cardGraficos, R.string.oficina_card_graficos, R.string.oficina_card_graficos_desc, R.drawable.ic_grafico, R.color.terminal_card)
            preencher(cardAceleracao, R.string.oficina_card_aceleracao, R.string.oficina_card_aceleracao_desc, R.drawable.ic_cronometro, R.color.veredito_atencao)
            preencher(cardCalibracao, R.string.oficina_card_calibracao, R.string.oficina_card_calibracao_desc, R.drawable.ic_posto, R.color.azul_primario)
            preencher(cardBateria, R.string.oficina_card_bateria, R.string.oficina_card_bateria_desc, R.drawable.ic_bateria, R.color.veredito_bom)
            preencher(cardInjecao, R.string.oficina_card_injecao, R.string.oficina_card_injecao_desc, R.drawable.ic_grafico, R.color.dtc_confirmado)
            preencher(cardFalhas, R.string.oficina_card_falhas, R.string.oficina_card_falhas_desc, R.drawable.ic_falha, R.color.dtc_confirmado)
            preencher(cardCheckup, R.string.oficina_card_checkup, R.string.oficina_card_checkup_desc, R.drawable.ic_checklist, R.color.status_ok)
            preencher(cardMisfire, R.string.oficina_card_misfire, R.string.oficina_card_misfire_desc, R.drawable.ic_falha, R.color.veredito_atencao)
            preencher(cardVarredura, R.string.oficina_card_varredura, R.string.oficina_card_varredura_desc, R.drawable.ic_varredura, R.color.azul_primario)
            preencher(cardTerminal, R.string.oficina_card_terminal, R.string.oficina_card_terminal_desc, R.drawable.ic_terminal, R.color.terminal_card)
            preencher(cardAtuadores, R.string.oficina_card_atuadores, R.string.oficina_card_atuadores_desc, R.drawable.ic_atuador, R.color.atuador_card)

            cardSensores.root.setOnClickListener { abrirConectada { SensoresActivity.intent(requireContext()) } }
            cardGraficos.root.setOnClickListener { abrirConectada { GraficosActivity.intent(requireContext()) } }
            cardAceleracao.root.setOnClickListener { abrirConectada { TesteAceleracaoActivity.intent(requireContext()) } }
            // A calibração abre sempre: não depende do scanner.
            cardCalibracao.root.setOnClickListener { startActivity(CalibracaoConsumoActivity.intent(requireContext(), veiculoId)) }
            cardBateria.root.setOnClickListener { abrirConectada { TesteBateriaActivity.intent(requireContext(), veiculoId) } }
            cardInjecao.root.setOnClickListener { abrirConectada { InjecaoActivity.intent(requireContext()) } }
            // O DTC abre sempre: a tela mostra o estado e só habilita ler/apagar com o scanner ligado.
            cardFalhas.root.setOnClickListener { startActivity(DiagnosticoErrosActivity.intent(requireContext(), veiculoId)) }
            cardCheckup.root.setOnClickListener {
                abrirConectada { CheckupObdActivity.intent(requireContext(), veiculoId, FerramentaCheckup.PRONTIDAO) }
            }
            cardMisfire.root.setOnClickListener {
                abrirConectada { CheckupObdActivity.intent(requireContext(), veiculoId, FerramentaCheckup.MISFIRE) }
            }
            cardVarredura.root.setOnClickListener {
                abrirConectada { TerminalObdActivity.intent(requireContext(), veiculoId, TerminalObdActivity.ABA_VARREDURA) }
            }
            cardTerminal.root.setOnClickListener {
                abrirConectada { TerminalObdActivity.intent(requireContext(), veiculoId, TerminalObdActivity.ABA_TERMINAL) }
            }
            cardAtuadores.root.setOnClickListener { abrirConectada { TesteAtuadoresActivity.intent(requireContext(), veiculoId) } }
            faixaConexao.setOnClickListener { irParaPainel() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.sessaoObd.estado.collect(::mostrarConexao)
            }
        }
    }

    private fun preencher(card: ItemCardOficinaBinding, @StringRes titulo: Int, @StringRes descricao: Int, @DrawableRes icone: Int, @ColorRes cor: Int) {
        card.textoTitulo.setText(titulo)
        card.textoDescricao.setText(descricao)
        card.imagemIcone.setImageResource(icone)
        card.fundoIcone.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), cor))
        card.root.contentDescription = "${getString(titulo)}. ${getString(descricao)}"
    }

    private fun mostrarConexao(estado: EstadoConexaoObd) = with(binding) {
        val conectado = estado is EstadoConexaoObd.Conectado
        textoConexao.definirTexto(getString(if (conectado) R.string.oficina_conectado else R.string.oficina_desconectado))
        val cor = if (conectado) R.color.status_ok else R.color.status_espera
        pontoConexao.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), cor))
        listOf(cardSensores, cardGraficos, cardAceleracao, cardBateria, cardInjecao, cardCheckup, cardMisfire, cardVarredura, cardTerminal, cardAtuadores).forEach { card ->
            card.textoRequisito.isVisible = !conectado
            card.root.alpha = if (conectado) 1f else ALFA_INDISPONIVEL
        }
    }

    private fun abrirConectada(intent: () -> Intent) {
        if (app.sessaoObd.estado.value !is EstadoConexaoObd.Conectado) {
            Snackbar.make(binding.root, R.string.oficina_requer_conexao, Snackbar.LENGTH_LONG)
                .setAction(R.string.oficina_ir_painel) { irParaPainel() }
                .show()
            return
        }
        startActivity(intent())
    }

    private fun irParaPainel() {
        (activity as? DiagnosticoActivity)?.mostrarAba(DiagnosticoPagerAdapter.ABA_PAINEL)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ALFA_INDISPONIVEL = 0.72f

        fun novo(veiculoId: Long) = OficinaFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
