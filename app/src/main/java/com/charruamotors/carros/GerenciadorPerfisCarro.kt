package com.charruamotors.carros

import android.content.Context
import com.charruamotors.direcao.desempenho.HistoricoDesempenho
import com.charruamotors.direcao.desempenho.MarcaDesempenho
import com.charruamotors.direcao.desempenho.ModalidadeTeste
import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.domain.manutencao.Odometro
import com.charruamotors.domain.manutencao.PlanoManutencao
import java.time.LocalDate
import com.charruamotors.oficina.bateria.HistoricoBateria
import com.charruamotors.oficina.bateria.RegistroTesteBateria
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import com.charruamotors.sistema.preferencias.ArmazenamentoComPrefixo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistência dos [PerfilCarro] (v2.3.0), num único arquivo `perfis_carro`: cada veículo no próprio
 * escopo `carro.<id>.` ([escopo]), o índice dos ids, o veículo ativo e a flag da migração das
 * preferências globais antigas. Nenhuma escrita de um veículo toca as chaves de outro.
 *
 * Sem `Context` nos testes JVM: basta um [ArmazenamentoChaveValor] em memória.
 */
class GerenciadorPerfisCarro(private val armazenamento: ArmazenamentoChaveValor) {

    constructor(context: Context) : this(
        ArmazenamentoChaveValor.deSharedPreferences(
            context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE),
        ),
    )

    private val _veiculoAtivo = MutableStateFlow(armazenamento.lerTexto(ATIVO)?.toLongOrNull()?.takeIf { it > 0 } ?: 0L)

    /** Veículo selecionado (o perfil em uso no Painel, HUD e Cluster); 0 = nenhum. */
    val veiculoAtivo: StateFlow<Long> = _veiculoAtivo.asStateFlow()

    private val _alteracoes = MutableStateFlow(0L)

    /** Conta as gravações de perfis (o seletor do topo e Meus Carros se redesenham). */
    val alteracoes: StateFlow<Long> = _alteracoes.asStateFlow()

    /** A migração das preferências globais (≤ v2.2.9) já rodou? */
    val migrado: Boolean get() = armazenamento.lerBooleano(MIGRADO, false)

    fun marcarMigrado() = armazenamento.gravar(mapOf(MIGRADO to true))

    /** Escopo de chaves só deste veículo (é o que a `PreferenciasPainel` do carro ativo recebe). */
    fun escopo(veiculoId: Long): ArmazenamentoComPrefixo = ArmazenamentoComPrefixo(armazenamento, prefixo(veiculoId))

    /** Veículos com perfil gravado. */
    fun ids(): Set<Long> = armazenamento.lerTexto(IDS).orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.toSet()

    fun existe(veiculoId: Long): Boolean = veiculoId in ids()

    /** Perfil de [veiculoId] lido agora do armazenamento (sem perfil gravado = o padrão). */
    fun ler(veiculoId: Long): PerfilCarro = CodecPerfilCarro.decodificar(veiculoId, escopo(veiculoId)::lerTexto)

    /** Perfis de todos os [veiculos], na ordem dada. */
    fun lerTodos(veiculos: Collection<Long>): List<PerfilCarro> = veiculos.map(::ler)

    @Synchronized
    fun salvar(perfil: PerfilCarro) {
        require(perfil.veiculoId > 0) { "perfil sem veículo" }
        escopo(perfil.veiculoId).gravar(CodecPerfilCarro.codificar(perfil))
        val ids = ids()
        if (perfil.veiculoId !in ids) gravarIds(ids + perfil.veiculoId)
        _alteracoes.value++
    }

    /** Lê, transforma e grava o perfil de [veiculoId] (só grava se mudou). Devolve o resultado. */
    @Synchronized
    fun atualizar(veiculoId: Long, transformar: (PerfilCarro) -> PerfilCarro): PerfilCarro {
        val atual = ler(veiculoId)
        val novo = transformar(atual).copy(veiculoId = veiculoId)
        if (novo != atual || !existe(veiculoId)) salvar(novo)
        return novo
    }

    /** Cria o perfil com [inicial] só se o veículo ainda não tiver um (nunca sobrescreve). */
    @Synchronized
    fun criarSeAusente(veiculoId: Long, inicial: () -> PerfilCarro): PerfilCarro {
        if (existe(veiculoId)) return ler(veiculoId)
        return inicial().copy(veiculoId = veiculoId).also(::salvar)
    }

    /**
     * "Duplicar" em Meus Carros: o novo veículo nasce com o Painel, HUD, Cluster e o perfil técnico
     * da [origem]; recordes, tempos, o Bluetooth do carro, o contador do tanque e o ajuste pela
     * bomba não vêm junto (são de cada carro físico).
     */
    @Synchronized
    fun duplicar(origem: Long, destino: Long): PerfilCarro {
        val base = ler(origem)
        val copia = base.copy(
            veiculoId = destino,
            recorde = RecordeDesempenho(),
            gatilhoNome = null,
            gatilhoEndereco = null,
            historicoDesempenho = HistoricoDesempenho(),
            litrosTanque = 0.0,
            // v2.8.0: o ajuste do adaptador é do chicote de cada carro, e o histórico é da bateria dele.
            offsetTensaoV = 0.0,
            historicoBateria = HistoricoBateria(),
            calibracaoConsumo = base.calibracaoConsumo.copy(ajustePercentual = 0.0),
            // v2.11.0: trocas e quilometragem são de cada carro físico.
            planoManutencao = PlanoManutencao(),
            odometro = null,
        )
        salvar(copia)
        return copia
    }

    /** v2.6.0: guarda um teste concluído no histórico (e no recorde 0–60 / 0–100) do veículo. */
    fun registrarTempo(veiculoId: Long, marca: MarcaDesempenho) {
        if (veiculoId <= 0) return
        val segundos = kotlin.math.round(marca.tempoMs / 10.0) / 100.0
        atualizar(veiculoId) {
            it.copy(
                historicoDesempenho = it.historicoDesempenho.com(marca),
                recorde = it.recorde.com(
                    segundos.takeIf { marca.modalidade == ModalidadeTeste.V0_60 },
                    segundos.takeIf { marca.modalidade == ModalidadeTeste.V0_100 },
                ),
            )
        }
    }

    /** v2.8.0: guarda um teste de bateria concluído no histórico do veículo. */
    fun registrarTesteBateria(veiculoId: Long, registro: RegistroTesteBateria) {
        if (veiculoId <= 0) return
        atualizar(veiculoId) { it.copy(historicoBateria = it.historicoBateria.com(registro)) }
    }

    /**
     * v2.6.0: soma ao contador do tanque. Grava só essa chave e não conta como alteração do perfil
     * (a sessão chama isto a cada minuto, e as telas não precisam se redesenhar).
     */
    @Synchronized
    fun acumularLitrosTanque(veiculoId: Long, litros: Double) {
        if (veiculoId <= 0 || !litros.isFinite() || litros <= 0) return
        if (!existe(veiculoId)) salvar(PerfilCarro(veiculoId))
        val total = ler(veiculoId).litrosTanque + litros
        escopo(veiculoId).gravar(mapOf(CodecPerfilCarro.LITROS_TANQUE to total.toString()))
    }

    // ---------------------------------------------------------------- odômetro e manutenção (v2.11.0)

    private val _odometros = MutableStateFlow(0L)

    /** Conta as gravações automáticas de odômetro (a aba Manutenções se redesenha, sem recarregar o perfil). */
    val odometros: StateFlow<Long> = _odometros.asStateFlow()

    /**
     * O cadastro do carro tem [kmCadastro]: se o odômetro se refere a outro valor (ou não existe),
     * passa a partir dele. Não cria perfil.
     */
    @Synchronized
    fun sincronizarOdometro(veiculoId: Long, kmCadastro: Int) {
        if (!existe(veiculoId) || ler(veiculoId).odometro?.kmCadastro == kmCadastro) return
        gravarOdometro(veiculoId, Odometro(kmCadastro, kmCadastro.toDouble()))
    }

    /**
     * Soma a distância de uma viagem real. Como o contador do tanque, grava só essa chave. Sem
     * referência ([sincronizarOdometro]) não há onde somar.
     */
    @Synchronized
    fun acumularQuilometros(veiculoId: Long, km: Double) {
        if (veiculoId <= 0 || !km.isFinite() || km <= 0) return
        val atual = ler(veiculoId).odometro ?: return
        gravarOdometro(veiculoId, atual.copy(km = atual.km + km))
    }

    /** Odômetro corrigido pelo usuário (vale mesmo se menor que o anterior). */
    fun definirOdometro(veiculoId: Long, kmCadastro: Int, km: Int) {
        if (veiculoId <= 0 || km < 0) return
        atualizar(veiculoId) { it.copy(odometro = Odometro(kmCadastro, km.toDouble())) }
    }

    /** "Registrar troca agora": o item passa a contar do odômetro atual e de [data]. */
    @Synchronized
    fun registrarTroca(veiculoId: Long, itemId: Long, kmCadastro: Int, data: LocalDate) {
        if (veiculoId <= 0) return
        atualizar(veiculoId) {
            it.copy(planoManutencao = it.planoManutencao.registrarTroca(itemId, Odometro.atual(it.odometro, kmCadastro), data))
        }
    }

    private fun gravarOdometro(veiculoId: Long, odometro: Odometro) {
        escopo(veiculoId).gravar(mapOf(CodecPerfilCarro.ODOMETRO to odometro.codificar()))
        _odometros.value++
    }

    /** Recorde 0–60 / 0–100 (só melhora) do veículo da sessão. */
    fun registrarRecorde(veiculoId: Long, t060: Double?, t0100: Double?) {
        if (veiculoId <= 0) return
        atualizar(veiculoId) { it.copy(recorde = it.recorde.com(t060, t0100)) }
    }

    /** Veículo excluído: apaga todas as chaves dele; se era o ativo, fica sem ativo. */
    @Synchronized
    fun excluir(veiculoId: Long) {
        escopo(veiculoId).gravar(CodecPerfilCarro.TODAS_AS_CHAVES.associateWith { null })
        gravarIds(ids() - veiculoId)
        if (_veiculoAtivo.value == veiculoId) ativar(0)
        _alteracoes.value++
    }

    /** Seleciona o veículo ativo (0 = nenhum). */
    @Synchronized
    fun ativar(veiculoId: Long) {
        val id = veiculoId.coerceAtLeast(0)
        if (id == _veiculoAtivo.value && armazenamento.lerTexto(ATIVO) != null) return
        armazenamento.gravar(mapOf(ATIVO to id.toString()))
        _veiculoAtivo.value = id
    }

    /** Restauração "substituir tudo" do backup: apaga todos os perfis e grava os do arquivo. */
    @Synchronized
    fun substituirTodos(perfis: List<PerfilCarro>, ativo: Long) {
        ids().forEach { escopo(it).gravar(CodecPerfilCarro.TODAS_AS_CHAVES.associateWith { null }) }
        gravarIds(emptySet())
        perfis.filter { it.veiculoId > 0 }.forEach(::salvar)
        ativar(if (perfis.any { it.veiculoId == ativo }) ativo else perfis.firstOrNull()?.veiculoId ?: 0)
        _alteracoes.value++
    }

    private fun gravarIds(ids: Set<Long>) = armazenamento.gravar(mapOf(IDS to ids.sorted().joinToString(",").ifEmpty { null }))

    companion object {
        const val ARQUIVO = "perfis_carro"
        const val IDS = "carros.ids"
        const val ATIVO = "carros.ativo"
        const val MIGRADO = "carros.migrado_v230"

        fun prefixo(veiculoId: Long) = "carro.$veiculoId."
    }
}
