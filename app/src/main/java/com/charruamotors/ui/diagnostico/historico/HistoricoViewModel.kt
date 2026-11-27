package com.charruamotors.ui.diagnostico.historico

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.data.repository.DiagnosticoRepository
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.obd.aprendizado.AprendizLogsVeiculo
import com.charruamotors.obd.aprendizado.ExclusaoLogs
import com.charruamotors.obd.aprendizado.IndiceLogsReais
import com.charruamotors.obd.aprendizado.ResultadoExclusao
import com.charruamotors.obd.log.AgrupadorSessoes
import com.charruamotors.obd.log.RelatorioSessaoTxt
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.laudo.ArquivoLaudoPdf
import com.charruamotors.ui.laudo.GeradorLaudoLog
import com.charruamotors.ui.laudo.ResultadoLaudoLog
import com.charruamotors.ui.oficina.replay.ArquivoLogCsv
import com.charruamotors.ui.oficina.replay.RepositorioLogsCsv
import com.charruamotors.ui.oficina.replay.TextoLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EventoHistorico {
    /** Relatório `.txt` da sessão lido: a tela mostra no diálogo (aberto no topo). */
    data class LogTecnico(val arquivo: ArquivoLogCsv, val texto: TextoLog) : EventoHistorico

    data class Laudo(val resultado: ResultadoLaudoLog) : EventoHistorico
    data class ErroLeitura(val detalhe: String) : EventoHistorico
}

/**
 * Aba Histórico: as sessões salvas em `Downloads/CharruaOBD` (CSV + relatório `.txt` agrupados em
 * [sessoes], com log técnico e laudo PDF) e as leituras salvas do veículo. Os abastecimentos ficam
 * no Fragment próprio, embutido na aba.
 */
class HistoricoViewModel(
    private val veiculoId: Long,
    private val repositorio: RepositorioLogsCsv,
    diagnostico: DiagnosticoRepository,
    private val indice: IndiceLogsReais,
    private val aprendizado: AprendizLogsVeiculo,
    private val geradorLaudo: GeradorLaudoLog,
    /** Trecho do nome dos arquivos do veículo (`Toyota_SW4`); null = veículo sem cadastro. */
    private val trechoVeiculo: suspend (Long) -> String?,
) : ViewModel() {

    private val arquivos = MutableStateFlow<List<ArquivoLogCsv>>(emptyList())

    private val _carregando = MutableStateFlow(false)
    val carregando: StateFlow<Boolean> = _carregando.asStateFlow()

    val sessoes: StateFlow<List<SessaoSalva>> = arquivos.map { AgrupadorSessoes.agrupar(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val leituras: StateFlow<List<LeituraObd>> = diagnostico.observarLeituras(veiculoId)
        .map { it.take(MAX_LEITURAS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _eventos = Channel<EventoHistorico>(Channel.BUFFERED)
    val eventos: Flow<EventoHistorico> = _eventos.receiveAsFlow()

    private var gerandoLaudo = false

    init {
        atualizar()
    }

    /** Relê a pasta (um log novo pode ter sido salvo pela sessão em segundo plano). */
    fun atualizar() {
        viewModelScope.launch {
            _carregando.value = true
            arquivos.value = runCatching { repositorio.listar() }.getOrDefault(emptyList())
            _carregando.value = false
        }
    }

    fun abrirLogTecnico(arquivo: ArquivoLogCsv) {
        viewModelScope.launch {
            _carregando.value = true
            try {
                _eventos.send(EventoHistorico.LogTecnico(arquivo, repositorio.lerTexto(arquivo.uri)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _eventos.send(EventoHistorico.ErroLeitura(e.message ?: e.javaClass.simpleName))
            } finally {
                _carregando.value = false
            }
        }
    }

    /** Laudo PDF da sessão, combinando o `.txt` e o `.csv` que ela tiver. */
    fun gerarLaudo(sessao: SessaoSalva, observacoes: String) {
        if (gerandoLaudo) return
        gerandoLaudo = true
        viewModelScope.launch {
            _carregando.value = true
            try {
                val resultado = geradorLaudo.gerar(observacoes) { repositorio.dadosDoLaudo(sessao.csv, sessao.txt, it) }
                _eventos.send(EventoHistorico.Laudo(resultado))
            } finally {
                gerandoLaudo = false
                _carregando.value = false
            }
        }
    }

    /** Logs (csv + txt, reais e simulados antigos) do veículo deste Histórico. */
    private suspend fun logsDoVeiculo(): List<ArquivoLogCsv> {
        val indexados = indice.entradas(veiculoId).map { it.nome }
        val trecho = runCatching { trechoVeiculo(veiculoId) }.getOrNull()
        return ExclusaoLogs.doVeiculo(arquivos.value, indexados, trecho)
    }

    /** Quantos arquivos o "Limpar logs" apagaria: (do veículo atual, todos). */
    suspend fun contagensParaLimpar(): Pair<Int, Int> = withContext(Dispatchers.IO) { logsDoVeiculo().size to arquivos.value.size }

    /** Apaga os arquivos da sessão e sincroniza o índice e o aprendizado do veículo. */
    fun apagar(sessao: SessaoSalva, aoConcluir: (ResultadoExclusao) -> Unit) = apagarLista({ sessao.arquivos }, aoConcluir)

    /** "Limpar logs": os do veículo atual ([todos] = false) ou todos os salvos. */
    fun limpar(todos: Boolean, aoConcluir: (ResultadoExclusao) -> Unit) =
        apagarLista({ if (todos) arquivos.value else logsDoVeiculo() }, aoConcluir)

    private fun apagarLista(alvo: suspend () -> List<ArquivoLogCsv>, aoConcluir: (ResultadoExclusao) -> Unit) {
        viewModelScope.launch {
            val resultado = withContext(Dispatchers.IO) { ExclusaoLogs.executar(alvo(), aprendizado, repositorio::apagar) }
            atualizar()
            aoConcluir(resultado)
        }
    }

    fun uriCompartilhavel(arquivo: ArquivoLogCsv) = repositorio.uriCompartilhavel(arquivo)

    companion object {
        const val MAX_LEITURAS = 50

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val veiculoId = createSavedStateHandle().get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L
                HistoricoViewModel(
                    veiculoId,
                    RepositorioLogsCsv(app),
                    app.diagnosticoRepository,
                    app.indiceLogsReais,
                    app.aprendizLogs,
                    GeradorLaudoLog(ArquivoLaudoPdf(app), app.preferenciasApp),
                ) { id -> app.veiculoRepository.buscarPorId(id)?.let { RelatorioSessaoTxt.trechoArquivo("${it.marca} ${it.modelo}".trim()) } }
            }
        }
    }
}
