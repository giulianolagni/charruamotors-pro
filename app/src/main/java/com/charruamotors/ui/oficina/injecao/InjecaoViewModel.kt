package com.charruamotors.ui.oficina.injecao

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.FreezeFrame
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.sessao.SessaoObd
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Situação da leitura do Modo 02. */
sealed interface EstadoFreezeFrame {
    data object SemConexao : EstadoFreezeFrame
    data object Carregando : EstadoFreezeFrame

    /** Modos 21/22: não há Modo 02. */
    data object NaoSuportado : EstadoFreezeFrame

    /** Nenhum DTC / quadro gravado. */
    data object Vazio : EstadoFreezeFrame

    data class Erro(val mensagem: String) : EstadoFreezeFrame
    data class Dados(val quadro: FreezeFrame, val descricao: String?) : EstadoFreezeFrame
}

/** Freeze Frame do Diagnóstico de Injeção (trims e sondas vivem na [SessaoObd.injecao]). */
class InjecaoViewModel(
    private val sessao: SessaoObd,
    private val catalogoDtc: CatalogoDtc,
) : ViewModel() {

    private val _freezeFrame = MutableStateFlow<EstadoFreezeFrame>(EstadoFreezeFrame.SemConexao)
    val freezeFrame: StateFlow<EstadoFreezeFrame> = _freezeFrame.asStateFlow()

    private var leitura: Job? = null

    init {
        // Carrega ao abrir e a cada reconexão.
        viewModelScope.launch {
            sessao.estado.map { it is EstadoConexaoObd.Conectado }.distinctUntilChanged().collect { conectado ->
                if (conectado) recarregar() else {
                    leitura?.cancel()
                    _freezeFrame.value = EstadoFreezeFrame.SemConexao
                }
            }
        }
    }

    fun recarregar() {
        if (leitura?.isActive == true) return
        val cliente = sessao.cliente
        if (cliente.estado.value !is EstadoConexaoObd.Conectado) {
            _freezeFrame.value = EstadoFreezeFrame.SemConexao
            return
        }
        if (cliente.infoConexao.value.modo != ModoComunicacaoEcu.OBD2_PADRAO) {
            _freezeFrame.value = EstadoFreezeFrame.NaoSuportado
            return
        }
        _freezeFrame.value = EstadoFreezeFrame.Carregando
        leitura = viewModelScope.launch {
            _freezeFrame.value = cliente.lerFreezeFrame().fold(
                onSuccess = { ff ->
                    if (ff.vazio) {
                        EstadoFreezeFrame.Vazio
                    } else {
                        EstadoFreezeFrame.Dados(ff, ff.dtc?.let { catalogoDtc.descrever(it, null).titulo })
                    }
                },
                onFailure = { EstadoFreezeFrame.Erro(it.message.orEmpty()) },
            )
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                InjecaoViewModel(app.sessaoObd, app.catalogoDtc)
            }
        }
    }
}
