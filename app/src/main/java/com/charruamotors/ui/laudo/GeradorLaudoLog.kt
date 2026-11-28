package com.charruamotors.ui.laudo

import com.charruamotors.laudo.DadosLaudo
import com.charruamotors.sistema.preferencias.PreferenciasApp
import kotlinx.coroutines.CancellationException

/** Desfecho do laudo de um log salvo. */
sealed interface ResultadoLaudoLog {
    data class Gerado(val laudo: LaudoGerado) : ResultadoLaudoLog
    data class Erro(val detalhe: String) : ResultadoLaudoLog

    /** O `.txt` escolhido não é um relatório de sessão do app. */
    data object SemRelatorio : ResultadoLaudoLog
}

/** Laudo PDF de log salvo (Histórico e replay), com a oficina/técnico das Configurações. */
class GeradorLaudoLog(
    private val arquivo: ArquivoLaudoPdf,
    private val preferencias: PreferenciasApp,
    private val pendencias: PendenciasManutencao,
) {

    /** [dados] recebe o instante de emissão e lê o log de nome [nomeArquivo]. */
    suspend fun gerar(
        opcoes: OpcoesLaudo,
        nomeArquivo: String,
        dados: suspend (Long) -> DadosLaudo?,
    ): ResultadoLaudoLog {
        val config = preferencias.config.value
        return try {
            val lidos = dados(System.currentTimeMillis()) ?: return ResultadoLaudoLog.SemRelatorio
            val manutencao = if (opcoes.incluirManutencao) {
                runCatching { pendencias.doLog(lidos, nomeArquivo) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val completo = lidos.copy(
                oficina = config.nomeOficina,
                tecnico = config.nomeTecnico,
                observacoes = opcoes.observacoes,
                manutencao = manutencao,
                unidades = config.conversor,
            )
            ResultadoLaudoLog.Gerado(arquivo.gerar(completo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            ResultadoLaudoLog.Erro("arquivo grande demais para a memória do aparelho")
        } catch (e: Exception) {
            ResultadoLaudoLog.Erro(e.message ?: e.javaClass.simpleName)
        }
    }
}
