package com.charruamotors.ui.alertas

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityConfigAlertasBinding
import com.charruamotors.domain.alertas.CanalAudio
import com.charruamotors.domain.alertas.ConfigAlertas
import com.charruamotors.domain.alertas.NivelAlerta
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * v2.4.0: alertas críticos de **um** carro (perfil isolado): liga/desliga cada regra, edita os
 * limites, escolhe o canal e o volume e testa os dois sons. Sem o id no intent, usa o carro ativo.
 */
class ConfigAlertasActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigAlertasBinding
    private val app get() = application as CharruaMotorsApp
    private var veiculoId = 0L

    private val canais = mutableMapOf<CanalAudio, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityConfigAlertasBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        veiculoId = intent.getLongExtra(EXTRA_VEICULO, 0).takeIf { it > 0 } ?: app.perfisCarro.veiculoAtivo.value
        for (canal in CanalAudio.entries) {
            val botao = RadioButton(this).apply {
                id = View.generateViewId()
                text = canal.titulo
            }
            canais[canal] = botao.id
            binding.grupoCanal.addView(botao)
        }
        binding.sliderVolume.addOnChangeListener { _, valor, _ -> mostrarVolume(valor.toInt()) }
        binding.botaoTestarCritico.setOnClickListener { testar(NivelAlerta.CRITICO) }
        binding.botaoTestarAtencao.setOnClickListener { testar(NivelAlerta.ATENCAO) }
        binding.botaoSalvar.setOnClickListener { salvar() }
        binding.botaoPadroes.setOnClickListener { preencher(ConfigAlertas()) }

        if (veiculoId <= 0) {
            binding.textoVeiculo.setText(R.string.alertas_sem_veiculo)
            binding.botaoSalvar.isEnabled = false
        } else {
            lifecycleScope.launch {
                val nome = app.veiculoRepository.buscarPorId(veiculoId)?.descricao.orEmpty()
                binding.textoVeiculo.text = getString(R.string.alertas_veiculo, nome)
            }
        }
        // Ao girar a tela, as Views guardam o que estava sendo digitado.
        if (savedInstanceState == null) {
            preencher(if (veiculoId > 0) app.perfisCarro.ler(veiculoId).alertas else ConfigAlertas())
        }
        mostrarVolume(binding.sliderVolume.value.toInt())
    }

    override fun onStop() {
        app.alarmeSonoro.parar()
        super.onStop()
    }

    private fun preencher(c: ConfigAlertas) = with(binding) {
        switchAgua.isChecked = c.aguaAtiva
        campoAguaAtencao.numero(c.aguaAtencaoC)
        campoAguaCritico.numero(c.aguaCriticoC)
        switchAtf.isChecked = c.atfAtivo
        campoAtfAtencao.numero(c.atfAtencaoC)
        campoAtfCritico.numero(c.atfCriticoC)
        switchTensao.isChecked = c.tensaoAtiva
        campoTensaoMin.numero(c.tensaoMinV)
        campoTensaoMax.numero(c.tensaoMaxV)
        switchDtc.isChecked = c.dtcAtivo
        switchComunicacao.isChecked = c.comunicacaoAtiva
        grupoCanal.check(canais.getValue(c.canal))
        // O Slider só aceita múltiplos do passo.
        sliderVolume.value = (c.volume / 5 * 5).toFloat()
    }

    /** O que está na tela; null = algum limite vazio ou não numérico. */
    private fun ler(): ConfigAlertas? = with(binding) {
        ConfigAlertas(
            aguaAtiva = switchAgua.isChecked,
            aguaAtencaoC = campoAguaAtencao.numero() ?: return null,
            aguaCriticoC = campoAguaCritico.numero() ?: return null,
            atfAtivo = switchAtf.isChecked,
            atfAtencaoC = campoAtfAtencao.numero() ?: return null,
            atfCriticoC = campoAtfCritico.numero() ?: return null,
            tensaoAtiva = switchTensao.isChecked,
            tensaoMinV = campoTensaoMin.numero() ?: return null,
            tensaoMaxV = campoTensaoMax.numero() ?: return null,
            dtcAtivo = switchDtc.isChecked,
            comunicacaoAtiva = switchComunicacao.isChecked,
            canal = canalEscolhido(),
            volume = sliderVolume.value.toInt(),
        )
    }

    private fun canalEscolhido(): CanalAudio =
        canais.entries.firstOrNull { it.value == binding.grupoCanal.checkedRadioButtonId }?.key ?: CanalAudio.MIDIA

    /** Toca com o canal e o volume da tela, mesmo antes de salvar. */
    private fun testar(nivel: NivelAlerta) {
        app.alarmeSonoro.tocar(nivel, ConfigAlertas(canal = canalEscolhido(), volume = binding.sliderVolume.value.toInt()))
    }

    private fun salvar() {
        val config = ler()
        val problema = if (config == null) getString(R.string.alertas_campo_invalido) else config.validar()?.mensagem
        if (config == null || problema != null) {
            Snackbar.make(binding.root, problema.orEmpty(), Snackbar.LENGTH_LONG).show()
            return
        }
        app.perfisCarro.atualizar(veiculoId) { it.copy(alertas = config) }
        Snackbar.make(binding.root, R.string.alertas_salvo, Snackbar.LENGTH_SHORT).show()
    }

    private fun mostrarVolume(volume: Int) {
        binding.textoVolume.text = getString(R.string.alertas_volume, volume)
    }

    private fun EditText.numero(valor: Double) =
        setText(if (valor % 1.0 == 0.0) valor.toInt().toString() else String.format(Locale.ROOT, "%.1f", valor))

    private fun EditText.numero(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    companion object {
        private const val EXTRA_VEICULO = "veiculo_id"

        fun intent(context: Context, veiculoId: Long = 0): Intent =
            Intent(context, ConfigAlertasActivity::class.java).putExtra(EXTRA_VEICULO, veiculoId)
    }
}
