package com.charruamotors.ui.carros

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.databinding.ActivityPerfisTecnicosBinding
import com.charruamotors.databinding.ItemPerfilTecnicoBinding
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.perfis.CarregadorPerfis
import com.charruamotors.domain.perfis.ItemPerfil
import com.charruamotors.domain.perfis.ListaPerfisTecnicos
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import kotlinx.coroutines.launch

/** Escolha do perfil técnico (faixas/motor) de um carro: um toque grava e volta. */
class PerfisTecnicosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPerfisTecnicosBinding
    private val app get() = application as CharruaMotorsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityPerfisTecnicosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val veiculoId = intent.getLongExtra(EXTRA_VEICULO_ID, 0L)
        lifecycleScope.launch {
            val veiculo = app.veiculoRepository.buscarPorId(veiculoId) ?: return@launch finish()
            binding.toolbar.subtitle = veiculo.descricao
            binding.campoBusca.doAfterTextChanged { mostrar(veiculo, it?.toString().orEmpty()) }
            mostrar(veiculo, binding.campoBusca.text?.toString().orEmpty())
        }
    }

    private fun mostrar(veiculo: Veiculo, busca: String) {
        val perfil = app.perfisCarro.ler(veiculo.id)
        val automatico = PerfilCarro(veiculo.id).resolver(veiculo).preset?.titulo ?: getString(R.string.perfil_tecnico_generico)
        binding.listaPerfis.removeAllViews()
        for (item in ListaPerfisTecnicos.montar(CarregadorPerfis.catalogo, PerfilCarro.OBD2_GENERICO, automatico, busca)) {
            when (item) {
                is ItemPerfil.Secao -> binding.listaPerfis.addView(
                    TextView(this).apply {
                        text = item.titulo
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                        val espaco = resources.getDimensionPixelSize(R.dimen.espaco_medio)
                        setPadding(0, espaco, 0, 0)
                    },
                )
                is ItemPerfil.Opcao -> {
                    val linha = ItemPerfilTecnicoBinding.inflate(layoutInflater, binding.listaPerfis, true)
                    val selecionado = item.id == perfil.perfilFaixasId
                    linha.textoTitulo.text = if (selecionado) getString(R.string.perfis_selecionado, item.titulo) else item.titulo
                    linha.textoDetalhe.text = item.detalhe
                    linha.root.isSelected = selecionado
                    linha.root.setOnClickListener { escolher(veiculo, item.id) }
                }
            }
        }
    }

    private fun escolher(veiculo: Veiculo, id: String?) {
        app.perfisCarro.atualizar(veiculo.id) { it.comPerfilEscolhido(id) }
        val emSessao = app.sessaoObd.estadoSessao.value.ativa
        val texto = getString(R.string.perfil_tecnico_salvo, veiculo.descricao) +
            if (emSessao) getString(R.string.perfil_tecnico_sessao_ativa) else ""
        Toast.makeText(this, texto.trim(), Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val EXTRA_VEICULO_ID = "veiculo_id"

        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, PerfisTecnicosActivity::class.java).putExtra(EXTRA_VEICULO_ID, veiculoId)
    }
}
