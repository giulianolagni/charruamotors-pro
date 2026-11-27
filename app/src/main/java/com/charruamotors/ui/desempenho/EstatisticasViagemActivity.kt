package com.charruamotors.ui.desempenho

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityEstatisticasViagemBinding
import com.charruamotors.databinding.ItemEstatisticaBinding
import com.charruamotors.obd.sessao.ViagemSessao
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.comoMoeda
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** v2.6.0: Histórico → Viagens → Estatísticas completas (sessão OBD em curso ou a última), sempre em unidades métricas. */
class EstatisticasViagemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEstatisticasViagemBinding
    private val app: CharruaMotorsApp get() = application as CharruaMotorsApp

    private lateinit var linhas: List<Pair<ItemEstatisticaBinding, (ViagemSessao) -> String?>>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityEstatisticasViagemBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        linhas = listOf(
            linha(R.string.viagem_distancia) { "${it.estatisticas.distanciaKm.comoNumero(2)} km" },
            linha(R.string.viagem_tempo_total) { duracao(it.estatisticas.segundosTotais) },
            linha(R.string.viagem_tempo_movimento) { duracao(it.estatisticas.segundosMovimento) },
            linha(R.string.viagem_tempo_parado) { duracao(it.estatisticas.segundosParado) },
            linha(R.string.viagem_velocidade_media) { v -> v.estatisticas.velocidadeMediaKmh?.let { "${it.comoNumero(1)} km/h" } },
            linha(R.string.viagem_velocidade_movimento) { v -> v.estatisticas.velocidadeMediaMovimentoKmh?.let { "${it.comoNumero(1)} km/h" } },
            linha(R.string.viagem_velocidade_maxima) { v -> v.estatisticas.velocidadeMaximaKmh?.let { "${it.roundToInt()} km/h" } },
            linha(R.string.viagem_rpm_medio) { v -> v.estatisticas.rpmMedio?.let { "${it.roundToInt()} rpm" } },
            linha(R.string.viagem_rpm_maximo) { v -> v.estatisticas.rpmMaximo?.let { "${it.roundToInt()} rpm" } },
            linha(R.string.viagem_km_l) { v -> v.estatisticas.kmPorLitro?.let { "${it.comoNumero(1)} km/L" } },
            linha(R.string.viagem_l_100) { v -> v.estatisticas.litrosPor100Km?.let { "${it.comoNumero(1)} L/100 km" } },
            linha(R.string.viagem_litros) { v -> v.estatisticas.litros.takeIf { it > 0 }?.let { "${it.comoNumero(2)} L" } },
            linha(R.string.viagem_custo) { v ->
                if (v.precoLitro == null) getString(R.string.viagem_sem_preco) else v.estatisticas.custo(v.precoLitro)?.comoMoeda()
            },
        )
        binding.botaoZerar.setOnClickListener { app.sessaoObd.zerarViagem() }
        binding.botaoCalibrar.setOnClickListener {
            val id = app.sessaoObd.estadoSessao.value.veiculoId.takeIf { it > 0 } ?: app.perfisCarro.veiculoAtivo.value
            startActivity(CalibracaoConsumoActivity.intent(this, id))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mostrar(app.sessaoObd.viagem.value)
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                app.sessaoObd.viagem.sample(INTERVALO_TELA_MS).collect(::mostrar)
            }
        }
    }

    private fun linha(@StringRes rotulo: Int, valor: (ViagemSessao) -> String?): Pair<ItemEstatisticaBinding, (ViagemSessao) -> String?> {
        val item = ItemEstatisticaBinding.inflate(layoutInflater, binding.lista, true)
        item.textoRotulo.setText(rotulo)
        return item to valor
    }

    private fun mostrar(viagem: ViagemSessao) {
        val vazia = viagem.estatisticas.segundosTotais <= 0 && viagem.veiculo == null && !app.sessaoObd.estadoSessao.value.ativa
        binding.textoContexto.definirTexto(
            when {
                vazia -> getString(R.string.viagem_vazia)
                else -> listOfNotNull(
                    viagem.veiculo?.let { getString(R.string.viagem_veiculo, it) } ?: getString(R.string.viagem_sem_veiculo),
                    getString(R.string.viagem_simulada).takeIf { viagem.simulada },
                ).joinToString("\n")
            },
        )
        val vazio = getString(R.string.valor_indisponivel)
        linhas.forEach { (item, valor) -> item.textoValor.definirTexto(valor(viagem) ?: vazio) }
        binding.botaoZerar.isEnabled = viagem.estatisticas.segundosTotais > 0
        binding.lista.isVisible = !vazia
    }

    /** `1 h 02 min 05 s`, `4 min 10 s` ou `35 s`. */
    private fun duracao(segundos: Double): String {
        val total = segundos.toLong()
        val h = total / 3600
        val min = total % 3600 / 60
        val s = total % 60
        return when {
            h > 0 -> "%d h %02d min %02d s".format(h, min, s)
            min > 0 -> "%d min %02d s".format(min, s)
            else -> "$s s"
        }
    }

    companion object {
        private const val INTERVALO_TELA_MS = 500L

        fun intent(context: Context): Intent = Intent(context, EstatisticasViagemActivity::class.java)
    }
}
