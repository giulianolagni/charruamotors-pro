package com.charruamotors.ui.diagnostico.manutencoes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.domain.manutencao.ItemComSituacao
import com.charruamotors.domain.manutencao.ItemManutencao
import com.charruamotors.domain.manutencao.Odometro
import com.charruamotors.domain.manutencao.RegistroTroca
import com.charruamotors.data.repository.ManutencaoRepository
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.domain.calculo.CalculosVeiculo
import com.charruamotors.domain.calculo.ProximaManutencao
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.ManutencaoComPecas
import com.charruamotors.domain.model.PecaTrocada
import com.charruamotors.domain.model.TipoManutencao
import com.charruamotors.domain.validacao.CampoManutencao
import com.charruamotors.domain.validacao.CampoPeca
import com.charruamotors.domain.validacao.ErroValidacao
import com.charruamotors.domain.validacao.FormularioManutencao
import com.charruamotors.domain.validacao.FormularioPeca
import com.charruamotors.domain.validacao.ValidadorManutencao
import com.charruamotors.domain.validacao.ValidadorPeca
import com.charruamotors.domain.validacao.paraDecimalOuNulo
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Dados da aba: serviços registrados, previsão das próximas manutenções (calculada no domínio, não
 * na UI) e a preventiva do carro (v2.11.0).
 */
data class ResumoManutencoes(
    val itens: List<ManutencaoComPecas>,
    val proximas: List<ProximaManutencao>,
    val odometroKm: Int = 0,
    val preventiva: List<ItemComSituacao> = emptyList(),
    val trocas: List<RegistroTroca> = emptyList(),
)

/** Eventos de uso único enviados para a UI. */
sealed interface EventoManutencao {
    data object Salva : EventoManutencao
    data object Excluida : EventoManutencao
}

/** Todos os dados digitados no formulário de manutenção. */
data class DadosFormularioManutencao(
    val formulario: FormularioManutencao,
    val tipo: TipoManutencao,
    val descricao: String,
    val oficina: String,
    val pecas: List<FormularioPeca>,
)

/** ViewModel da aba Manutenções: lista, previsão e CRUD com validação (ValidadorManutencao). */
class ManutencoesViewModel(
    private val repository: ManutencaoRepository,
    veiculoRepository: VeiculoRepository,
    private val perfis: GerenciadorPerfisCarro,
    savedStateHandle: SavedStateHandle,
    private val hoje: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    val veiculoId: Long = savedStateHandle.get<Long>(DiagnosticoActivity.EXTRA_VEICULO_ID) ?: 0L

    /** Km do cadastro no último resumo (a referência do odômetro). */
    private var kmCadastro = 0

    private val reavaliacoes = MutableStateFlow(0)

    /** A data de hoje pode ter mudado (a tela voltou): recalcula as situações. */
    fun reavaliar() {
        reavaliacoes.value++
    }

    /** Recalculado quando muda a lista, a km do veículo, o perfil do carro ou o odômetro das viagens. */
    val resumo: StateFlow<ResumoManutencoes?> = combine(
        repository.observarPorVeiculo(veiculoId),
        veiculoRepository.observarPorId(veiculoId),
        perfis.alteracoes,
        perfis.odometros,
        reavaliacoes,
    ) { itens, veiculo, _, _, _ ->
        if (veiculo == null) return@combine ResumoManutencoes(itens, emptyList())
        kmCadastro = veiculo.kmAtual
        val perfil = perfis.ler(veiculoId)
        val odometro = Odometro.atual(perfil.odometro, veiculo.kmAtual)
        ResumoManutencoes(
            itens = itens,
            proximas = CalculosVeiculo.proximasManutencoes(itens.map { it.manutencao }, odometro, hoje()),
            odometroKm = odometro,
            preventiva = perfil.planoManutencao.avaliar(odometro, hoje()),
            trocas = perfil.planoManutencao.historico,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    fun salvarItem(item: ItemManutencao) {
        perfis.atualizar(veiculoId) { it.copy(planoManutencao = it.planoManutencao.com(item)) }
    }

    fun removerItem(id: Long) {
        perfis.atualizar(veiculoId) { it.copy(planoManutencao = it.planoManutencao.sem(id)) }
    }

    fun registrarTroca(id: Long) = perfis.registrarTroca(veiculoId, id, kmCadastro, hoje())

    fun corrigirOdometro(km: Int) = perfis.definirOdometro(veiculoId, kmCadastro, km)

    private val _erros = MutableStateFlow<Map<CampoManutencao, ErroValidacao>>(emptyMap())
    val erros: StateFlow<Map<CampoManutencao, ErroValidacao>> = _erros.asStateFlow()

    /** Erros por linha de peça (índice da linha no formulário). */
    private val _errosPecas = MutableStateFlow<Map<Int, Map<CampoPeca, ErroValidacao>>>(emptyMap())
    val errosPecas: StateFlow<Map<Int, Map<CampoPeca, ErroValidacao>>> = _errosPecas.asStateFlow()

    private val _salvando = MutableStateFlow(false)
    val salvando: StateFlow<Boolean> = _salvando.asStateFlow()

    private val _eventos = Channel<EventoManutencao>(Channel.BUFFERED)
    val eventos: Flow<EventoManutencao> = _eventos.receiveAsFlow()

    /** Read de um registro para edição. */
    suspend fun carregar(id: Long): ManutencaoComPecas? = repository.buscarPorId(id)

    /** Create (id = 0) ou Update, depois de validar as regras de negócio. */
    fun salvar(id: Long, dados: DadosFormularioManutencao) {
        if (_salvando.value) return
        viewModelScope.launch {
            _salvando.value = true
            try {
                // Novo registro: a km não pode ser menor que a atual do veículo. Ao editar um registro
                // antigo isso não se aplica (a km atual do veículo já avançou desde então).
                val kmMinimo = if (id == 0L) repository.kmMinimo(veiculoId) else 0
                val erros = ValidadorManutencao.validar(dados.formulario, hoje(), kmMinimo)

                val pecasPreenchidas = dados.pecas.withIndex().filterNot { it.value.vazia }
                val errosPecas = pecasPreenchidas
                    .associate { (indice, peca) -> indice to ValidadorPeca.validar(peca) }
                    .filterValues { it.isNotEmpty() }

                _erros.value = erros
                _errosPecas.value = errosPecas
                if (erros.isNotEmpty() || errosPecas.isNotEmpty()) return@launch

                val manutencao = Manutencao(
                    id = id,
                    veiculoId = veiculoId,
                    data = dados.formulario.data!!,
                    km = dados.formulario.km.trim().toInt(),
                    tipo = dados.tipo,
                    descricao = dados.descricao.trim().ifBlank { null },
                    oficina = dados.oficina.trim().ifBlank { null },
                    custoTotal = dados.formulario.custo.paraDecimalOuNulo()!!,
                )
                val pecas = pecasPreenchidas.map { (_, peca) ->
                    PecaTrocada(
                        nome = peca.nome.trim(),
                        quantidade = peca.quantidade.trim().toInt(),
                        valorUnitario = peca.valorUnitario.paraDecimalOuNulo()!!,
                    )
                }
                repository.salvar(manutencao, pecas)
                _eventos.send(EventoManutencao.Salva)
            } finally {
                _salvando.value = false
            }
        }
    }

    /** Delete (as peças são removidas em cascata). */
    fun excluir(manutencao: Manutencao) {
        viewModelScope.launch {
            repository.excluir(manutencao)
            _eventos.send(EventoManutencao.Excluida)
        }
    }

    fun limparErro(campo: CampoManutencao) {
        if (campo in _erros.value) _erros.value -= campo
    }

    /** Descarta os erros ao abrir um formulário novo. */
    fun limparErros() {
        _erros.value = emptyMap()
        _errosPecas.value = emptyMap()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as CharruaMotorsApp
                ManutencoesViewModel(app.manutencaoRepository, app.veiculoRepository, app.perfisCarro, createSavedStateHandle())
            }
        }
    }
}
