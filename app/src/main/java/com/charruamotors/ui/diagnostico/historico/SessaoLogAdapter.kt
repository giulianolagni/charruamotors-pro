package com.charruamotors.ui.diagnostico.historico

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemCabecalhoSimuladosBinding
import com.charruamotors.databinding.ItemSessaoLogBinding
import com.charruamotors.obd.log.LinhaLog
import com.charruamotors.obd.log.OrganizadorLogs
import com.charruamotors.obd.log.SessaoGravada
import com.charruamotors.ui.oficina.replay.ArquivoLogCsv
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

typealias SessaoSalva = SessaoGravada<ArquivoLogCsv>

/**
 * Sessões gravadas, um cartão por sessão: da mais recente para a mais antiga, com as reais direto na
 * lista e as do Modo Simulado na pasta retrátil "Simulados" no topo.
 */
class SessaoLogAdapter(
    private val aoReplay: (ArquivoLogCsv) -> Unit,
    private val aoLogTecnico: (ArquivoLogCsv) -> Unit,
    private val aoLaudo: (SessaoSalva) -> Unit,
    private val aoMaisAcoes: (SessaoSalva) -> Unit,
) : ListAdapter<LinhaLog<SessaoSalva>, RecyclerView.ViewHolder>(Diferenca) {

    private class Item(val binding: ItemSessaoLogBinding) : RecyclerView.ViewHolder(binding.root)
    private class Cabecalho(val binding: ItemCabecalhoSimuladosBinding) : RecyclerView.ViewHolder(binding.root)

    private var sessoes: List<SessaoSalva> = emptyList()
    private var simuladosExpandidos = false

    /** Recebe as sessões em qualquer ordem: a lista é ordenada e separada aqui. */
    fun definirSessoes(lista: List<SessaoSalva>) {
        sessoes = lista
        atualizarLinhas()
    }

    private fun atualizarLinhas() = submitList(OrganizadorLogs.montarLinhas(sessoes, simuladosExpandidos))

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is LinhaLog.CabecalhoSimulados -> TIPO_CABECALHO
        is LinhaLog.Arquivo -> TIPO_SESSAO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TIPO_CABECALHO) {
            Cabecalho(ItemCabecalhoSimuladosBinding.inflate(inflater, parent, false))
        } else {
            Item(ItemSessaoLogBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val linha = getItem(position)) {
            is LinhaLog.CabecalhoSimulados -> ligarCabecalho(holder as Cabecalho, linha)
            is LinhaLog.Arquivo -> ligarSessao(holder as Item, linha)
        }
    }

    private fun ligarCabecalho(holder: Cabecalho, linha: LinhaLog.CabecalhoSimulados) {
        val contexto = holder.itemView.context
        holder.binding.textoTituloSimulados.text =
            contexto.resources.getQuantityString(R.plurals.logs_simulados_titulo, linha.quantidade, linha.quantidade)
        holder.binding.imagemExpandir.rotation = if (linha.expandido) 180f else 0f
        holder.itemView.contentDescription = contexto.getString(
            if (linha.expandido) R.string.logs_simulados_recolher else R.string.logs_simulados_expandir,
        )
        holder.itemView.setOnClickListener {
            simuladosExpandidos = !simuladosExpandidos
            atualizarLinhas()
        }
    }

    private fun ligarSessao(holder: Item, linha: LinhaLog.Arquivo<SessaoSalva>) = with(holder.binding) {
        val sessao = linha.item
        val contexto = root.context
        textoTitulo.text = sessao.titulo
        textoData.text = FORMATO_DATA.format(Instant.ofEpochMilli(OrganizadorLogs.instante(sessao)).atZone(ZoneId.systemDefault()))
        badgeSimulado.isVisible = linha.simulado

        fun tamanho(a: ArquivoLogCsv) = Formatter.formatShortFileSize(contexto, a.tamanhoBytes)
        val csv = sessao.csv
        val txt = sessao.txt
        badgeCsv.isVisible = csv != null
        badgeCsv.text = csv?.let { contexto.getString(R.string.historico_badge_csv, tamanho(it)) }
        badgeTxt.isVisible = txt != null
        badgeTxt.text = txt?.let { contexto.getString(R.string.historico_badge_txt, tamanho(it)) }

        botaoReplay.isVisible = csv != null
        botaoReplay.setOnClickListener { csv?.let(aoReplay) }
        botaoLogTecnico.isVisible = txt != null
        botaoLogTecnico.setOnClickListener { txt?.let(aoLogTecnico) }
        botaoLaudo.setOnClickListener { aoLaudo(sessao) }
        botaoMais.setOnClickListener { aoMaisAcoes(sessao) }
        root.setOnClickListener { if (csv != null) aoReplay(csv) else txt?.let(aoLogTecnico) }
        root.setOnLongClickListener {
            aoMaisAcoes(sessao)
            true
        }
    }

    private object Diferenca : DiffUtil.ItemCallback<LinhaLog<SessaoSalva>>() {
        override fun areItemsTheSame(a: LinhaLog<SessaoSalva>, b: LinhaLog<SessaoSalva>) = when {
            a is LinhaLog.CabecalhoSimulados && b is LinhaLog.CabecalhoSimulados -> true
            a is LinhaLog.Arquivo && b is LinhaLog.Arquivo -> a.item.arquivos.first().uri == b.item.arquivos.first().uri
            else -> false
        }

        override fun areContentsTheSame(a: LinhaLog<SessaoSalva>, b: LinhaLog<SessaoSalva>) = a == b
    }

    private companion object {
        const val TIPO_SESSAO = 0
        const val TIPO_CABECALHO = 1
        val FORMATO_DATA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")
    }
}
