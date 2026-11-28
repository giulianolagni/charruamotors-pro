package com.charruamotors.sistema.sensores

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.charruamotors.direcao.forcag.MedidorForcaG
import com.charruamotors.direcao.forcag.Vetor3

/**
 * Liga os sensores de movimento do aparelho ao [MedidorForcaG], numa thread própria (nunca a do
 * OBD2 nem a principal). Só escuta enquanto houver algum dono ativo ([usar]): a tela do medidor, o
 * cartão do Painel ou a gravação de uma sessão real.
 */
class SensorForcaG(context: Context) {

    val medidor = MedidorForcaG(SystemClock::elapsedRealtime)

    private val gerenciador = context.applicationContext.getSystemService(SensorManager::class.java)
    private val acelerometro: Sensor? = gerenciador?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linear: Sensor? = gerenciador?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    /** O aparelho tem acelerômetro? (É o mínimo: ele também serve à calibração.) */
    val disponivel: Boolean get() = acelerometro != null

    private val donos = mutableSetOf<Any>()
    private var thread: HandlerThread? = null

    private val ouvinte = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (v.size < 3) return
            val vetor = Vetor3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> medidor.acelerometro(vetor, event.timestamp)
                Sensor.TYPE_LINEAR_ACCELERATION -> medidor.aceleracaoLinear(vetor, event.timestamp)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** O [dono] passa a precisar (ou deixa de precisar) das leituras. Idempotente. */
    @Synchronized
    fun usar(dono: Any, ativo: Boolean) {
        if (ativo) donos.add(dono) else donos.remove(dono)
        if (donos.isEmpty()) parar() else iniciar()
    }

    private fun iniciar() {
        if (thread != null) return
        val g = gerenciador ?: return
        val a = acelerometro ?: return
        val nova = HandlerThread("forca-g", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
        val handler = Handler(nova.looper)
        medidor.reiniciarSinal(comAceleracaoLinear = linear != null)
        g.registerListener(ouvinte, a, PERIODO_US, handler)
        linear?.let { g.registerListener(ouvinte, it, PERIODO_US, handler) }
        thread = nova
    }

    private fun parar() {
        val atual = thread ?: return
        gerenciador?.unregisterListener(ouvinte)
        atual.quitSafely()
        thread = null
    }

    private companion object {
        /** 25 Hz; o [MedidorForcaG] reamostra, porque o sistema pode entregar mais rápido. */
        const val PERIODO_US = 40_000
    }
}
