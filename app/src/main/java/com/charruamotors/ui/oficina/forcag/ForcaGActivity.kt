package com.charruamotors.ui.oficina.forcag

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
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
import com.charruamotors.databinding.ActivityForcaGBinding
import com.charruamotors.direcao.forcag.EstadoForcaG
import com.charruamotors.obd.ModoObd
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Medidor de Força G (aba Oficina): mostrador ao vivo, calibração do suporte, picos da sessão e a
 * opção de gravar a Força G no CSV. Não usa a conexão OBD2; a tela fica acesa enquanto aberta.
 */
class ForcaGActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForcaGBinding
    private val app get() = application as CharruaMotorsApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityForcaGBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val disponivel = app.sensorForcaG.disponivel
        binding.botaoCalibrar.isEnabled = disponivel
        binding.botaoCalibrar.setOnClickListener {
            Snackbar.make(binding.root, ForcaGUi.calibrar(this), Snackbar.LENGTH_LONG).show()
        }
        binding.botaoZerarPicos.setOnClickListener { app.sensorForcaG.medidor.zerarPicos() }
        binding.switchGravar.setOnCheckedChangeListener { _, marcado -> app.preferenciasApp.definirGravarForcaG(marcado) }

        val semLinear = getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) == null

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Acessar a sessão também garante a calibração do carro em uso carregada.
                launch {
                    app.sessaoObd.modo.collect { modo ->
                        val avisos = listOfNotNull(
                            getString(R.string.forca_g_sem_sensor).takeIf { !disponivel },
                            getString(R.string.forca_g_sem_linear).takeIf { disponivel && semLinear },
                            getString(R.string.forca_g_simulado).takeIf { modo == ModoObd.SIMULADO },
                        )
                        binding.textoAviso.isVisible = avisos.isNotEmpty()
                        binding.textoAviso.definirTexto(avisos.joinToString("\n"))
                    }
                }
                launch {
                    app.preferenciasApp.config.collect {
                        if (binding.switchGravar.isChecked != it.gravarForcaG) binding.switchGravar.isChecked = it.gravarForcaG
                    }
                }
                launch { app.sensorForcaG.medidor.estado.collect(::mostrar) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.sensorForcaG.usar(this, true)
    }

    override fun onStop() {
        app.sensorForcaG.usar(this, false)
        super.onStop()
    }

    private fun mostrar(estado: EstadoForcaG) = with(binding) {
        mostradorG.estado = estado
        textoSituacao.definirTexto(ForcaGUi.situacao(this@ForcaGActivity, estado))
        textoAtual.definirTexto(ForcaGUi.atual(this@ForcaGActivity, estado))
        textoPicos.definirTexto(ForcaGUi.picos(this@ForcaGActivity, estado))
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ForcaGActivity::class.java)
    }
}
