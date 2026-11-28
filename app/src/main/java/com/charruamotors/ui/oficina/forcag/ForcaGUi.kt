package com.charruamotors.ui.oficina.forcag

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.direcao.forcag.EstadoForcaG
import com.charruamotors.direcao.forcag.ResultadoCalibracao
import com.charruamotors.direcao.forcag.RotacaoTela
import com.charruamotors.ui.util.comoNumero

/** Partes comuns à tela do Medidor de Força G e ao cartão do Painel. */
object ForcaGUi {

    /** "Zerar / Calibrar suporte" com a rotação atual da tela; devolve a mensagem do resultado. */
    fun calibrar(context: Context): Int {
        val app = context.applicationContext as CharruaMotorsApp
        if (!app.sensorForcaG.disponivel) return R.string.forca_g_sem_sensor
        val tela = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return when (app.calibrarForcaG(RotacaoTela.deCodigo(tela?.rotation ?: 0))) {
            ResultadoCalibracao.OK -> R.string.forca_g_calibrado
            ResultadoCalibracao.SEM_LEITURAS -> R.string.forca_g_calibrar_sem_leituras
            ResultadoCalibracao.EM_MOVIMENTO -> R.string.forca_g_calibrar_movimento
            ResultadoCalibracao.POSICAO_INVALIDA -> R.string.forca_g_calibrar_posicao
        }
    }

    fun situacao(context: Context, estado: EstadoForcaG): String = context.getString(
        when {
            !estado.calibrado -> R.string.forca_g_nao_calibrado
            estado.atual == null -> R.string.forca_g_aguardando
            else -> R.string.forca_g_calibrado
        },
    )

    fun atual(context: Context, estado: EstadoForcaG): String = context.getString(
        R.string.forca_g_atual,
        estado.atual?.longitudinal?.comoNumero(2) ?: "—",
        estado.atual?.lateral?.comoNumero(2) ?: "—",
    )

    fun picos(context: Context, estado: EstadoForcaG): String = with(estado.picos) {
        context.getString(
            R.string.forca_g_picos,
            aceleracao.comoNumero(2),
            frenagem.comoNumero(2),
            curvaEsquerda.comoNumero(2),
            curvaDireita.comoNumero(2),
        )
    }
}
