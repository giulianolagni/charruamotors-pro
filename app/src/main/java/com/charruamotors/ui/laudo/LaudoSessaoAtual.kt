package com.charruamotors.ui.laudo

import com.charruamotors.CharruaMotorsApp
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.laudo.DadosLaudo
import com.charruamotors.laudo.DiagnosticoLaudo
import com.charruamotors.laudo.DtcLaudo
import com.charruamotors.laudo.FreezeFrameLaudo
import com.charruamotors.laudo.LaudoDaSessao
import com.charruamotors.laudo.MonitorLaudo
import com.charruamotors.laudo.VeiculoLaudo
import com.charruamotors.obd.EstadoMonitor
import com.charruamotors.obd.FreezeFrame
import com.charruamotors.obd.StatusProntidao
import com.charruamotors.obd.log.RelatorioSessaoTxt
import com.charruamotors.ui.diagnostico.obd.DtcExibicao
import com.charruamotors.ui.diagnostico.obd.ResultadoDiagnostico
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Monta o instantâneo da sessão OBD em curso e gera o laudo dela. */
class LaudoSessaoAtual(private val app: CharruaMotorsApp) {

    private val arquivo = ArquivoLaudoPdf(app)

    /** Chamar na main thread (o contexto da sessão): as leituras da sessão são tiradas de uma vez. */
    suspend fun gerar(diagnostico: ResultadoDiagnostico, prontidao: StatusProntidao?, observacoes: String): LaudoGerado {
        val sessao = app.sessaoObd
        val config = app.preferenciasApp.config.value
        val conexao = sessao.infoConexao.value
        val impressao = sessao.impressaoEcu.value
        val vin = sessao.vin.value
        val veiculoId = sessao.estadoSessao.value.veiculoId
        val dados = DadosLaudo(
            origem = LaudoDaSessao.origem(sessao.modo.value, sessao.estado.value is EstadoConexaoObd.Conectado),
            emitidoEmMs = System.currentTimeMillis(),
            oficina = config.nomeOficina,
            tecnico = config.nomeTecnico,
            fonte = "sessão em curso",
            veiculo = VeiculoLaudo(
                vin = vin,
                protocolo = conexao.protocolo,
                camada = conexao.nomeModo,
                adaptador = conexao.adaptador,
                calid = impressao?.calid,
                combustivelEcu = impressao?.descricaoCombustivel,
                tcm = impressao?.tcmDetectado?.let { if (it) "detectado (${impressao.tcmCabecalho})" else "não detectado" },
            ),
            diagnostico = converter(diagnostico, prontidao),
            resumo = LaudoDaSessao.resumo(sessao.historico.estatisticas(), sessao.viagem.value.estatisticas),
            alertas = sessao.alertasDisparados(),
            observacoes = observacoes,
        )
        return arquivo.gerar(dados.copy(veiculo = identificar(veiculoId, dados.veiculo)))
    }

    /** Cadastro e perfil isolado do veículo da sessão (só leitura, sem travar o gerenciador). */
    private suspend fun identificar(veiculoId: Long, base: VeiculoLaudo): VeiculoLaudo {
        if (veiculoId <= 0) return base
        return withContext(Dispatchers.IO) {
            val veiculo = app.veiculoRepository.buscarPorId(veiculoId) ?: return@withContext base
            val perfil = app.perfisCarro.ler(veiculoId)
            val tecnico = if (perfil.obd2Generico) PerfilCarro.TAG_GENERICO else perfil.resolver(veiculo).preset?.titulo
            base.copy(
                perfil = veiculo.descricao,
                perfilTecnico = tecnico,
                placa = veiculo.placaFormatada,
                modelo = veiculo.modelo,
            )
        }
    }

    private fun converter(d: ResultadoDiagnostico, prontidao: StatusProntidao?): DiagnosticoLaudo {
        fun lista(codigos: List<DtcExibicao>) = codigos.map { DtcLaudo(it.codigo, it.info.titulo) }
        return DiagnosticoLaudo(
            milAcesa = prontidao?.milAcesa,
            ativos = lista(d.confirmados),
            pendentes = d.pendentes?.let(::lista),
            permanentes = d.permanentes?.let(::lista),
            cambio = d.cambio?.let(::lista),
            freezeFrame = d.freezeFrame?.takeIf { !it.vazio }?.let { quadro(it, d.infoFreezeFrame?.titulo) },
            monitores = prontidao?.suportados?.map { MonitorLaudo(it.nome, it.estado == EstadoMonitor.PRONTO) },
        )
    }

    private fun quadro(ff: FreezeFrame, titulo: String?): FreezeFrameLaudo {
        val definicoes = app.catalogoPids.pids.associateBy { it.comando }
        val leituras = ff.valores.map { (comando, valor) ->
            val def = definicoes[comando]
            val unidade = def?.unidade?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            (def?.nome ?: comando) to RelatorioSessaoTxt.numero(valor, def?.casasDecimais ?: 1) + unidade
        }
        return FreezeFrameLaudo(ff.dtc?.let { codigo -> listOfNotNull(codigo, titulo).joinToString(" · ") }, leituras)
    }
}
