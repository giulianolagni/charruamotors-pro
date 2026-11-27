package com.charruamotors.ui.laudo

import com.charruamotors.CharruaMotorsApp
import com.charruamotors.domain.manutencao.Odometro
import com.charruamotors.domain.manutencao.VeiculoDoLog
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.laudo.DadosLaudo
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** Resumo das manutenções vencidas ou próximas de um carro, para o laudo (só leitura do perfil). */
class PendenciasManutencao(private val app: CharruaMotorsApp) {

    suspend fun doVeiculo(veiculoId: Long): List<Pair<String, String>> {
        val veiculo = veiculoId.takeIf { it > 0 }?.let { app.veiculoRepository.buscarPorId(it) } ?: return emptyList()
        return linhas(veiculo)
    }

    /** Do carro de um log salvo, se der para saber qual é; senão nada. */
    suspend fun doLog(dados: DadosLaudo, nomeArquivo: String): List<Pair<String, String>> {
        val veiculos = app.veiculoRepository.observarTodos().first()
        return VeiculoDoLog.identificar(dados.veiculo.placa, nomeArquivo, veiculos)?.let(::linhas).orEmpty()
    }

    private fun linhas(veiculo: Veiculo): List<Pair<String, String>> {
        val perfil = app.perfisCarro.ler(veiculo.id)
        return perfil.planoManutencao.linhasDoLaudo(Odometro.atual(perfil.odometro, veiculo.kmAtual), LocalDate.now())
    }
}
