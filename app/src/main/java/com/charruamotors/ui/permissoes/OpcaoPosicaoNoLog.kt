package com.charruamotors.ui.permissoes

import android.Manifest
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.obd.sessao.ObdSessaoService

/**
 * Liga e desliga "Gravar posição GPS no log" (Configurações e atalho do log). A permissão de
 * localização precisa só é pedida quando o usuário liga a opção; negada, a opção continua desligada e
 * nada mais muda (a conexão OBD2 não depende dela). Criar junto com a tela (registra o pedido).
 */
class OpcaoPosicaoNoLog(
    caller: ActivityResultCaller,
    private val app: () -> CharruaMotorsApp,
    /** Terminou (concedida ou negada): a tela volta a refletir a preferência. */
    private val aoDecidir: (concedida: Boolean) -> Unit,
) {
    private val pedido = caller.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultado ->
        val concedida = resultado[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (concedida) ativar()
        aoDecidir(concedida)
    }

    fun definir(ligar: Boolean) {
        when {
            !ligar -> {
                app().preferenciasApp.definirGravarPosicaoGps(false)
                app().reavaliarGps()
            }
            app().velocidadeGps.temPermissao() -> ativar()
            else -> pedido.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun ativar() {
        val a = app()
        a.preferenciasApp.definirGravarPosicaoGps(true)
        a.reavaliarGps()
        ObdSessaoService.revalidarLocalizacao(a)
    }
}
