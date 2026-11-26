package com.charruamotors.sistema.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.charruamotors.domain.alertas.CanalAudio
import com.charruamotors.domain.alertas.ConfigAlertas
import com.charruamotors.domain.alertas.NivelAlerta

/**
 * v2.4.0: bipes dos alertas com [ToneGenerator]. Enquanto tocam, pede o foco de áudio
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (a música do carro abaixa) e o devolve no fim.
 * **Crítico**: sequência rápida e aguda; **Atenção**: bipe duplo moderado. Pode ser chamado de
 * qualquer thread; um alarme novo substitui o que estiver tocando.
 */
class AlarmeSonoro(context: Context) {

    private class Padrao(val tom: Int, val repeticoes: Int, val duracaoMs: Int, val pausaMs: Int)

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val principal = Handler(Looper.getMainLooper())

    private var gerador: ToneGenerator? = null
    private var foco: AudioFocusRequest? = null

    fun tocar(nivel: NivelAlerta, config: ConfigAlertas) {
        principal.post { iniciar(nivel, config) }
    }

    fun parar() {
        principal.post { liberar() }
    }

    private fun iniciar(nivel: NivelAlerta, config: ConfigAlertas) {
        liberar()
        if (config.volume <= 0) return
        val (stream, uso) = when (config.canal) {
            CanalAudio.MIDIA -> AudioManager.STREAM_MUSIC to AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            CanalAudio.ALARME -> AudioManager.STREAM_ALARM to AudioAttributes.USAGE_ALARM
            CanalAudio.NOTIFICACAO -> AudioManager.STREAM_NOTIFICATION to AudioAttributes.USAGE_NOTIFICATION_EVENT
        }
        val padrao = if (nivel == NivelAlerta.CRITICO) CRITICO else ATENCAO
        try {
            val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(uso).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                .build()
            // Foco recusado (ex.: ligação em andamento) não impede o aviso.
            audio?.requestAudioFocus(pedido)
            foco = pedido
            val tons = ToneGenerator(stream, config.volume.coerceIn(0, ToneGenerator.MAX_VOLUME))
            gerador = tons
            // A espera inicial dá tempo de a música abaixar e o Bluetooth do carro abrir o áudio.
            val passo = (padrao.duracaoMs + padrao.pausaMs).toLong()
            repeat(padrao.repeticoes) { i ->
                principal.postAtTime(
                    { runCatching { tons.startTone(padrao.tom, padrao.duracaoMs) } },
                    FICHA,
                    android.os.SystemClock.uptimeMillis() + ESPERA_INICIAL_MS + i * passo,
                )
            }
            principal.postAtTime(
                { liberar() },
                FICHA,
                android.os.SystemClock.uptimeMillis() + ESPERA_INICIAL_MS + padrao.repeticoes * passo + FOLGA_FINAL_MS,
            )
        } catch (e: RuntimeException) {
            // ToneGenerator falha quando o sistema está sem canais de áudio.
            Log.w(TAG, "Não foi possível tocar o alarme", e)
            liberar()
        }
    }

    private fun liberar() {
        principal.removeCallbacksAndMessages(FICHA)
        gerador?.let {
            runCatching { it.stopTone() }
            runCatching { it.release() }
        }
        gerador = null
        foco?.let { pedido -> runCatching { audio?.abandonAudioFocusRequest(pedido) } }
        foco = null
    }

    private companion object {
        const val TAG = "AlarmeSonoro"

        /** Marca os passos agendados, para cancelar só eles. */
        val FICHA = Any()
        const val ESPERA_INICIAL_MS = 350L
        const val FOLGA_FINAL_MS = 300L
        val CRITICO = Padrao(ToneGenerator.TONE_CDMA_HIGH_L, repeticoes = 6, duracaoMs = 140, pausaMs = 80)
        val ATENCAO = Padrao(ToneGenerator.TONE_CDMA_MED_L, repeticoes = 2, duracaoMs = 220, pausaMs = 180)
    }
}
