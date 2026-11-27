package com.charruamotors.ui.desempenho

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
import com.charruamotors.databinding.ActivityCalibracaoConsumoBinding
import com.charruamotors.domain.calculo.CalibracaoConsumo
import com.charruamotors.domain.calculo.CombustivelCalibracao
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.rotulo
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * v2.6.0: calibração do consumo do perfil isolado de **um** carro: cilindrada, combustível,
 * eficiência volumétrica (carros sem MAF), preço do litro e ajuste fino, com o ajuste calculado
 * pelos litros da bomba contra o contador do app. Sem o id no intent, usa o carro ativo.
 */
class CalibracaoConsumoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibracaoConsumoBinding
    private val app get() = application as CharruaMotorsApp
    private var veiculoId = 0L

    /** Botão de cada opção; a chave null é o "Automático". */
    private val combustiveis = mutableMapOf<CombustivelCalibracao?, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityCalibracaoConsumoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        veiculoId = intent.getLongExtra(EXTRA_VEICULO, 0).takeIf { it > 0 } ?: app.perfisCarro.veiculoAtivo.value
        for (opcao in listOf<CombustivelCalibracao?>(null) + CombustivelCalibracao.entries) {
            val botao = RadioButton(this).apply {
                // Id estável: o RadioGroup restaura a escolha depois de girar a tela.
                id = IDS_COMBUSTIVEL[combustiveis.size]
                text = opcao?.titulo ?: getString(R.string.calibracao_combustivel_auto)
            }
            combustiveis[opcao] = botao.id
            binding.grupoCombustivel.addView(botao)
        }
        binding.botaoSalvar.setOnClickListener { salvar() }
        binding.botaoZerarContador.setOnClickListener {
            zerarContador()
            avisar(getString(R.string.calibracao_contador_zerado))
        }
        binding.botaoAplicarAjuste.setOnClickListener { aplicarAjuste() }

        if (veiculoId <= 0) {
            binding.textoVeiculo.setText(R.string.calibracao_sem_veiculo)
            listOf<View>(binding.botaoSalvar, binding.botaoZerarContador, binding.botaoAplicarAjuste).forEach { it.isEnabled = false }
        } else {
            lifecycleScope.launch {
                val veiculo = app.veiculoRepository.buscarPorId(veiculoId) ?: return@launch
                binding.textoVeiculo.text = getString(R.string.calibracao_veiculo, veiculo.descricao)
                val cadastro = CalibracaoConsumo().parametros(veiculo.modelo, veiculo.tipoCombustivel, veiculo.calibracaoConsumo)
                binding.textoCadastro.text = getString(
                    R.string.calibracao_cadastro,
                    cadastro.cilindradaLitros.comoNumero(1),
                    veiculo.tipoCombustivel.rotulo(this@CalibracaoConsumoActivity),
                )
            }
        }
        // Ao girar a tela, as Views guardam o que estava sendo digitado.
        if (savedInstanceState == null) preencher(lerSalva())
        mostrarContador(preencherCampo = savedInstanceState == null)
    }

    override fun onResume() {
        super.onResume()
        mostrarContador(preencherCampo = false)
    }

    private fun lerSalva(): CalibracaoConsumo =
        if (veiculoId > 0) app.perfisCarro.ler(veiculoId).calibracaoConsumo else CalibracaoConsumo()

    private fun preencher(c: CalibracaoConsumo) = with(binding) {
        campoCilindrada.numero(c.cilindradaLitros)
        grupoCombustivel.check(combustiveis.getValue(c.combustivel))
        campoEficiencia.numero(c.eficienciaVolumetrica?.let { it * 100 })
        campoPreco.numero(c.precoLitro)
        campoAjuste.numero(c.ajustePercentual)
    }

    private fun mostrarContador(preencherCampo: Boolean) {
        if (veiculoId <= 0) return
        app.sessaoObd.descarregarLitrosTanque()
        val litros = app.perfisCarro.ler(veiculoId).litrosTanque
        binding.textoContador.text = getString(R.string.calibracao_contador, litros.comoNumero(2))
        if (preencherCampo) binding.campoLitrosApp.numero(litros.takeIf { it > 0 })
    }

    private fun zerarContador() {
        app.sessaoObd.descarregarLitrosTanque()
        app.perfisCarro.atualizar(veiculoId) { it.copy(litrosTanque = 0.0) }
        binding.campoLitrosBomba.setText("")
        mostrarContador(preencherCampo = true)
    }

    private fun salvar() {
        val campos = listOf(binding.campoCilindrada, binding.campoEficiencia, binding.campoPreco, binding.campoAjuste)
        if (campos.any { it.text.toString().isNotBlank() && it.numero() == null }) {
            avisar(getString(R.string.calibracao_campo_invalido))
            return
        }
        val nova = CalibracaoConsumo(
            cilindradaLitros = binding.campoCilindrada.numero(),
            combustivel = combustiveis.entries.firstOrNull { it.value == binding.grupoCombustivel.checkedRadioButtonId }?.key,
            eficienciaVolumetrica = binding.campoEficiencia.numero()?.let { it / 100 },
            precoLitro = binding.campoPreco.numero(),
            ajustePercentual = binding.campoAjuste.numero() ?: 0.0,
        )
        nova.validar()?.let {
            avisar(it)
            return
        }
        // Os litros já contados com a calibração anterior são gravados antes da troca...
        app.sessaoObd.descarregarLitrosTanque()
        val mudouEstimativa = !nova.mesmaEstimativa(lerSalva())
        app.perfisCarro.atualizar(veiculoId) { it.copy(calibracaoConsumo = nova) }
        // ...e não servem para comparar com a bomba se a estimativa mudou.
        if (mudouEstimativa) zerarContador()
        avisar(getString(if (mudouEstimativa) R.string.calibracao_salvo_contador_zerado else R.string.calibracao_salvo))
    }

    private fun aplicarAjuste() {
        val salva = lerSalva()
        val bomba = binding.campoLitrosBomba.numero()
        val estimado = binding.campoLitrosApp.numero()
        val novo = if (bomba != null && estimado != null) salva.ajustePelaBomba(bomba, estimado) else null
        if (novo == null) {
            avisar(getString(R.string.calibracao_ajuste_recusado, CalibracaoConsumo.LITROS_MINIMOS_AJUSTE.toInt()))
            return
        }
        app.sessaoObd.descarregarLitrosTanque()
        app.perfisCarro.atualizar(veiculoId) { it.copy(calibracaoConsumo = salva.copy(ajustePercentual = novo)) }
        binding.campoAjuste.numero(novo)
        zerarContador()
        avisar(getString(R.string.calibracao_aplicado, novo.comoNumero(1)))
    }

    private fun avisar(texto: String) = Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()

    /** Sem separador de milhar, para o próprio campo conseguir reler o valor. */
    private fun EditText.numero(valor: Double?) =
        setText(valor?.let { String.format(Locale.ROOT, "%.2f", it).trimEnd('0').trimEnd('.').replace('.', ',') }.orEmpty())

    private fun EditText.numero(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    companion object {
        private const val EXTRA_VEICULO = "veiculo_id"

        private val IDS_COMBUSTIVEL: List<Int> by lazy { List(CombustivelCalibracao.entries.size + 1) { View.generateViewId() } }

        fun intent(context: Context, veiculoId: Long = 0): Intent =
            Intent(context, CalibracaoConsumoActivity::class.java).putExtra(EXTRA_VEICULO, veiculoId)
    }
}
