package com.charruamotors.ui.diagnostico.manutencoes

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.DialogItemPreventivaBinding
import com.charruamotors.databinding.ItemPlanoManutencaoBinding
import com.charruamotors.databinding.ItemPreventivaBinding
import com.charruamotors.domain.manutencao.ItemManutencao
import com.charruamotors.domain.manutencao.PresetManutencao
import com.charruamotors.domain.manutencao.RegistroTroca
import com.charruamotors.domain.manutencao.StatusManutencao
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoTexto
import com.charruamotors.ui.util.mostrarSeletorData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate

/** Cor do selo de cada situação (também usada no badge da navegação inferior). */
val StatusManutencao.cor: Int
    get() = when (this) {
        StatusManutencao.EM_DIA -> R.color.status_ok
        StatusManutencao.PROXIMO -> R.color.veredito_atencao
        StatusManutencao.VENCIDO -> R.color.veredito_ruim
    }

private const val KM_MAXIMO = 9_999_999

/** Topo da aba Manutenções (1 item): odômetro, itens da preventiva e ações. */
class PlanoManutencaoAdapter(
    private val aoCorrigirOdometro: () -> Unit,
    private val aoAdicionar: () -> Unit,
    private val aoEditar: (ItemManutencao) -> Unit,
    private val aoRegistrarTroca: (ItemManutencao) -> Unit,
    private val aoVerHistorico: () -> Unit,
) : RecyclerView.Adapter<PlanoManutencaoAdapter.ViewHolder>() {

    private var resumo: ResumoManutencoes? = null

    class ViewHolder(val binding: ItemPlanoManutencaoBinding) : RecyclerView.ViewHolder(binding.root)

    fun mostrar(novo: ResumoManutencoes) {
        val tinha = resumo != null
        resumo = novo
        if (tinha) notifyItemChanged(0, Unit) else notifyItemInserted(0)
    }

    override fun getItemCount() = if (resumo != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPlanoManutencaoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val r = resumo ?: return
        with(holder.binding) {
            val contexto = root.context
            textoOdometro.text = contexto.getString(R.string.preventiva_odometro, r.odometroKm.comoMilhar())
            botaoOdometro.setOnClickListener { aoCorrigirOdometro() }
            botaoAdicionarItem.setOnClickListener { aoAdicionar() }

            val vencidos = r.preventiva.count { it.situacao.status == StatusManutencao.VENCIDO }
            val proximos = r.preventiva.count { it.situacao.status == StatusManutencao.PROXIMO }
            textoPendencias.isVisible = vencidos + proximos > 0
            textoPendencias.text = contexto.getString(R.string.preventiva_pendencias, vencidos, proximos)
            textoPendencias.pintar(if (vencidos > 0) StatusManutencao.VENCIDO else StatusManutencao.PROXIMO)

            textoItensVazio.isVisible = r.preventiva.isEmpty()
            listaItens.removeAllViews()
            r.preventiva.forEach { (item, situacao) ->
                val linha = ItemPreventivaBinding.inflate(LayoutInflater.from(contexto), listaItens, true)
                linha.textoNome.text = item.nome
                linha.textoStatus.text = situacao.status.titulo
                linha.textoStatus.pintar(situacao.status)
                linha.textoDetalhe.text = listOfNotNull(
                    situacao.descricao,
                    contexto.getString(R.string.preventiva_ultima, item.kmUltimaTroca.comoMilhar(), item.dataUltimaTroca.comoTexto()),
                    item.observacao.takeIf { it.isNotBlank() },
                ).joinToString("\n")
                linha.root.setOnClickListener { aoEditar(item) }
                linha.botaoRegistrar.setOnClickListener { aoRegistrarTroca(item) }
            }

            botaoHistoricoTrocas.isVisible = r.trocas.isNotEmpty()
            botaoHistoricoTrocas.text = contexto.getString(R.string.preventiva_historico, r.trocas.size)
            botaoHistoricoTrocas.setOnClickListener { aoVerHistorico() }
            textoListaVazia.isVisible = r.itens.isEmpty()
        }
    }

    private fun android.view.View.pintar(status: StatusManutencao) {
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, status.cor))
    }
}

/** Diálogos da manutenção preventiva. */
object DialogosPreventiva {

    fun escolherPreset(contexto: Context, aoEscolher: (PresetManutencao) -> Unit) {
        val presets = PresetManutencao.entries
        MaterialAlertDialogBuilder(contexto)
            .setTitle(R.string.preventiva_escolher_preset)
            .setItems(presets.map { it.titulo }.toTypedArray()) { _, i -> aoEscolher(presets[i]) }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    /** Item novo a partir do preset, contado de hoje e do odômetro atual. */
    fun novoItem(preset: PresetManutencao, odometroKm: Int) = ItemManutencao(
        nome = if (preset == PresetManutencao.PERSONALIZADO) "" else preset.titulo,
        kmUltimaTroca = odometroKm,
        dataUltimaTroca = LocalDate.now(),
        intervaloKm = preset.intervaloKm,
        intervaloMeses = preset.intervaloMeses,
    )

    /** Formulário do item; com campo inválido o diálogo continua aberto. [aoExcluir] só na edição. */
    fun editarItem(
        contexto: Context,
        fragmentos: FragmentManager,
        item: ItemManutencao,
        aoSalvar: (ItemManutencao) -> Unit,
        aoExcluir: () -> Unit,
    ) {
        val form = DialogItemPreventivaBinding.inflate(LayoutInflater.from(contexto))
        var data = item.dataUltimaTroca
        form.campoNome.setText(item.nome)
        form.campoKm.setText(item.kmUltimaTroca.toString())
        form.campoData.setText(data.comoTexto())
        form.campoIntervaloKm.setText(item.intervaloKm?.toString().orEmpty())
        form.campoIntervaloMeses.setText(item.intervaloMeses?.toString().orEmpty())
        form.campoObservacao.setText(item.observacao)
        form.campoData.setOnClickListener {
            fragmentos.mostrarSeletorData(data) {
                data = it
                form.campoData.setText(it.comoTexto())
            }
        }
        val construtor = MaterialAlertDialogBuilder(contexto)
            .setTitle(if (item.id == 0L) R.string.preventiva_novo else R.string.preventiva_editar)
            .setView(form.root)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.botao_salvar, null)
        if (item.id != 0L) {
            construtor.setNeutralButton(R.string.botao_excluir) { _, _ ->
                confirmar(contexto, contexto.getString(R.string.preventiva_excluir_confirmar, item.nome), aoExcluir)
            }
        }
        val dialogo = construtor.create()
        dialogo.setOnShowListener {
            dialogo.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                fun validar(layout: TextInputLayout, valido: Boolean, mensagem: Int): Boolean {
                    layout.error = if (valido) null else contexto.getString(mensagem)
                    return valido
                }
                fun texto(campo: TextInputEditText) = campo.text?.toString()?.trim().orEmpty()
                // Vazio = sem o intervalo; preenchido tem de ser positivo.
                fun intervaloValido(campo: TextInputEditText) = texto(campo).let { it.isEmpty() || (it.toIntOrNull() ?: 0) > 0 }

                val nome = texto(form.campoNome)
                val km = texto(form.campoKm).toIntOrNull()?.takeIf { it in 0..KM_MAXIMO }
                val ok = listOf(
                    validar(form.layoutNome, nome.isNotEmpty(), R.string.preventiva_erro_obrigatorio),
                    validar(form.layoutKm, km != null, R.string.preventiva_erro_km),
                    validar(form.layoutIntervaloKm, intervaloValido(form.campoIntervaloKm), R.string.preventiva_erro_intervalo),
                    validar(form.layoutIntervaloMeses, intervaloValido(form.campoIntervaloMeses), R.string.preventiva_erro_intervalo),
                ).all { it }
                if (!ok || km == null) return@setOnClickListener
                aoSalvar(
                    item.copy(
                        nome = nome,
                        kmUltimaTroca = km,
                        dataUltimaTroca = data,
                        intervaloKm = texto(form.campoIntervaloKm).toIntOrNull(),
                        intervaloMeses = texto(form.campoIntervaloMeses).toIntOrNull(),
                        observacao = texto(form.campoObservacao),
                    ),
                )
                dialogo.dismiss()
            }
        }
        dialogo.show()
    }

    fun corrigirOdometro(contexto: Context, atual: Int, aoSalvar: (Int) -> Unit) {
        val campo = TextInputEditText(contexto).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(atual.toString())
        }
        val layout = TextInputLayout(contexto).apply {
            hint = contexto.getString(R.string.preventiva_odometro_campo)
            val margem = resources.getDimensionPixelSize(R.dimen.margem_tela)
            setPadding(margem, margem / 2, margem, 0)
            addView(campo)
        }
        val dialogo = MaterialAlertDialogBuilder(contexto)
            .setTitle(R.string.preventiva_odometro_campo)
            .setView(layout)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.botao_salvar, null)
            .create()
        dialogo.setOnShowListener {
            dialogo.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val km = campo.text?.toString()?.toIntOrNull()?.takeIf { it in 0..KM_MAXIMO }
                if (km == null) {
                    layout.error = contexto.getString(R.string.preventiva_odometro_invalido)
                    return@setOnClickListener
                }
                aoSalvar(km)
                dialogo.dismiss()
            }
        }
        dialogo.show()
    }

    fun historico(contexto: Context, trocas: List<RegistroTroca>) {
        val linhas = trocas.map {
            contexto.getString(R.string.preventiva_historico_linha, it.data.comoTexto(), it.nome, it.km.comoMilhar())
        }
        MaterialAlertDialogBuilder(contexto)
            .setTitle(R.string.preventiva_historico_titulo)
            .setItems(linhas.toTypedArray(), null)
            .setPositiveButton(R.string.botao_fechar, null)
            .show()
    }

    fun confirmar(contexto: Context, mensagem: String, aoConfirmar: () -> Unit) {
        MaterialAlertDialogBuilder(contexto)
            .setMessage(mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.botao_confirmar) { _, _ -> aoConfirmar() }
            .show()
    }
}
