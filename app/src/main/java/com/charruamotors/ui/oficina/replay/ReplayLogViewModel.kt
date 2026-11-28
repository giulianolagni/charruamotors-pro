package com.charruamotors.ui.oficina.replay

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.oficina.replay.AnaliseReplay
import com.charruamotors.oficina.replay.ColunaLog
import com.charruamotors.oficina.replay.CsvInvalidoException
import com.charruamotors.oficina.replay.EstatisticaSerie
import com.charruamotors.oficina.replay.ExportadorGpx
import com.charruamotors.oficina.replay.JanelaReplay
import com.charruamotors.oficina.replay.LogCsv
import com.charruamotors.oficina.replay.MarcoGpx
import com.charruamotors.oficina.replay.PicoTrajeto
import com.charruamotors.oficina.replay.TrajetoGps
import com.charruamotors.oficina.replay.emUnidades
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.laudo.ArquivoLaudoPdf
import com.charruamotors.ui.laudo.GeradorLaudoLog
import com.charruamotors.ui.laudo.OpcoesLaudo
import com.charruamotors.ui.laudo.PendenciasManutencao
import com.charruamotors.ui.laudo.ResultadoLaudoLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Log aberto no replay. */
data class LogAberto(
    val nome: String,
    val uri: Uri,
    val log: LogCsv,
    val total: JanelaReplay,
    val duracaoMinimaMs: Long,
    /** v2.12.0: trajeto das colunas `gps_*`; null = o log não tem coordenadas válidas. */
    val trajeto: TrajetoGps? = null,
    /** Onde cada pico (RPM, MAP, rail) aconteceu no trajeto. */
    val picos: List<PicoTrajeto> = emptyList(),
)

/** Resumo de um sensor selecionado: estatísticas da janela e o valor no cursor. */
data class ResumoSerie(
    val coluna: Int,
    /** Posição na seleção (0–3): define a cor. */
    val posicao: Int,
    val definicao: ColunaLog,
    val estatistica: EstatisticaSerie?,
    val valorCursor: Double?,
)

data class ResumoReplay(val janela: JanelaReplay, val series: List<ResumoSerie>, val instanteCursor: Long?)

sealed interface EventoReplay {
    data class Erro(val detalhe: String) : EventoReplay
    data object MaximoSensores : EventoReplay
    data class Laudo(val resultado: ResultadoLaudoLog) : EventoReplay
    data class Gpx(val gpx: GpxGerado) : EventoReplay
    data class ErroGpx(val detalhe: String) : EventoReplay
}

/**
 * Replay de um log CSV (aberto pela aba Histórico): guarda a seleção de até 4 colunas, a janela de
 * tempo e o cursor (sobrevivem à rotação) e calcula o resumo Mínimo/Máximo/Média da janela visível.
 */
class ReplayLogViewModel(
    private val repositorio: RepositorioLogsCsv,
    private val geradorLaudo: GeradorLaudoLog,
    private val conversor: () -> ConversorUnidades,
    private val arquivoGpx: ArquivoGpx,
) : ViewModel() {

    private var gerandoLaudo = false
    private var gerandoGpx = false

    /** Exporta o trajeto do log aberto, com os [marcos] dos picos já nas unidades da tela. */
    fun exportarGpx(marcos: List<MarcoGpx>) {
        val aberto = _log.value ?: return
        val trajeto = aberto.trajeto ?: return
        if (gerandoGpx) return
        gerandoGpx = true
        viewModelScope.launch {
            try {
                val conteudo = withContext(Dispatchers.Default) {
                    ExportadorGpx.gerar(aberto.nome, trajeto, aberto.log.tempoAbsoluto, marcos)
                }
                _eventos.send(EventoReplay.Gpx(arquivoGpx.salvar(aberto.nome, conteudo)))
            } catch (e: IOException) {
                _eventos.send(EventoReplay.ErroGpx(e.message ?: "falha de escrita"))
            } finally {
                gerandoGpx = false
            }
        }
    }

    /** Laudo PDF do log aberto (e do relatório `.txt` da mesma sessão). */
    fun gerarLaudo(uri: Uri, nome: String, opcoes: OpcoesLaudo) {
        if (gerandoLaudo) return
        gerandoLaudo = true
        viewModelScope.launch {
            _carregando.value = true
            try {
                val resultado = geradorLaudo.gerar(opcoes, nome) { repositorio.dadosDoLaudo(uri, nome, it) }
                _eventos.send(EventoReplay.Laudo(resultado))
            } finally {
                gerandoLaudo = false
                _carregando.value = false
            }
        }
    }

    private val _carregando = MutableStateFlow(false)
    val carregando: StateFlow<Boolean> = _carregando.asStateFlow()

    private val _log = MutableStateFlow<LogAberto?>(null)
    val log: StateFlow<LogAberto?> = _log.asStateFlow()

    private val _selecionados = MutableStateFlow<List<Int>>(emptyList())
    val selecionados: StateFlow<List<Int>> = _selecionados.asStateFlow()

    private val _janela = MutableStateFlow<JanelaReplay?>(null)
    val janela: StateFlow<JanelaReplay?> = _janela.asStateFlow()

    private val _cursor = MutableStateFlow<Int?>(null)
    val cursor: StateFlow<Int?> = _cursor.asStateFlow()

    private val _eventos = Channel<EventoReplay>(Channel.BUFFERED)
    val eventos: Flow<EventoReplay> = _eventos.receiveAsFlow()

    /** Recalculado a cada gesto (fora da main thread: logs longos têm dezenas de milhares de linhas). */
    val resumo: StateFlow<ResumoReplay?> = combine(_log, _selecionados, _janela, _cursor) { aberto, sel, janela, cursor ->
        if (aberto == null || janela == null) return@combine null
        val log = aberto.log
        val faixa = AnaliseReplay.faixaIndices(log.instantesMs, janela)
        ResumoReplay(
            janela = janela,
            series = sel.mapIndexed { posicao, coluna ->
                ResumoSerie(
                    coluna = coluna,
                    posicao = posicao,
                    definicao = log.colunas[coluna],
                    estatistica = AnaliseReplay.estatisticas(log.series[coluna], faixa),
                    valorCursor = cursor?.let { log.series[coluna].getOrNull(it) }?.takeUnless { it.isNaN() },
                )
            },
            instanteCursor = cursor?.let { log.instantesMs.getOrNull(it) },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var carregamento: Job? = null

    /** CSV escolhido na lista do Histórico. */
    fun abrir(uri: Uri, nome: String) = carregar(uri) { nome }

    /** Documento escolhido em "Abrir CSV externo" (Storage Access Framework). */
    fun abrirDocumento(uri: Uri) = carregar(uri) { repositorio.nomeDe(uri) }

    private fun carregar(uri: Uri, nome: suspend () -> String) {
        carregamento?.cancel()
        carregamento = viewModelScope.launch {
            _carregando.value = true
            try {
                val bruto = repositorio.abrir(uri)
                val log = bruto.emUnidades(conversor())
                val total = AnaliseReplay.janelaCompleta(log)
                val (trajeto, picos) = withContext(Dispatchers.Default) {
                    val t = TrajetoGps.de(bruto)
                    t to t?.picos(bruto).orEmpty()
                }
                _log.value = LogAberto(nome(), uri, log, total, AnaliseReplay.duracaoMinima(log), trajeto, picos)
                _selecionados.value = AnaliseReplay.selecaoInicial(log)
                _janela.value = total
                _cursor.value = null
            } catch (e: CsvInvalidoException) {
                _eventos.send(EventoReplay.Erro(e.message ?: "formato inválido"))
            } catch (e: IOException) {
                _eventos.send(EventoReplay.Erro(e.message ?: "falha de leitura"))
            } catch (e: SecurityException) {
                _eventos.send(EventoReplay.Erro("sem permissão para ler o arquivo"))
            } catch (e: OutOfMemoryError) {
                _eventos.send(EventoReplay.Erro("arquivo grande demais para a memória do aparelho"))
            } finally {
                _carregando.value = false
            }
        }
    }

    fun alternarSensor(coluna: Int) {
        val nova = AnaliseReplay.alternarSelecao(_selecionados.value, coluna)
        if (nova == null) {
            _eventos.trySend(EventoReplay.MaximoSensores)
            return
        }
        _selecionados.value = nova
    }

    fun mudarJanela(janela: JanelaReplay) {
        _janela.value = janela
    }

    fun moverCursor(indice: Int?) {
        _cursor.value = indice
    }

    fun verTudo() {
        _log.value?.let { _janela.value = it.total }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                val gerador = GeradorLaudoLog(ArquivoLaudoPdf(app), app.preferenciasApp, PendenciasManutencao(app))
                ReplayLogViewModel(RepositorioLogsCsv(app), gerador, { app.preferenciasApp.config.value.conversor }, ArquivoGpx(app))
            }
        }
    }
}
