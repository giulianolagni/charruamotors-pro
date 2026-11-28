package com.charruamotors.carros

import com.charruamotors.direcao.desempenho.HistoricoDesempenho
import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.domain.calculo.CalibracaoConsumo
import com.charruamotors.domain.manutencao.Odometro
import com.charruamotors.domain.manutencao.PlanoManutencao
import com.charruamotors.direcao.painel.ClusterPainel
import com.charruamotors.direcao.painel.CodecPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.domain.alertas.ConfigAlertas
import com.charruamotors.obd.pid.PidCustomizado
import com.charruamotors.oficina.bateria.HistoricoBateria
import com.charruamotors.oficina.bateria.OffsetAdaptador
import com.charruamotors.oficina.sensores.PresetsGrafico

/**
 * Chaves de um [PerfilCarro] dentro do escopo `carro.<id>.` (v2.3.0). O Painel/HUD usa as mesmas
 * chaves do [CodecPainel] (a [com.charruamotors.direcao.painel.PreferenciasPainel] do carro ativo
 * grava direto nelas); o resto do perfil fica nas chaves abaixo. Também é o formato do perfil no
 * backup `.json` (objeto `painel` + campos), então o round-trip é o mesmo nos dois lugares.
 */
object CodecPerfilCarro {

    /** Versão do esquema do perfil por veículo (gravada em [SCHEMA]); sobe quando um campo mudar de significado. */
    const val VERSAO_SCHEMA = 1

    const val SCHEMA = "schema"
    const val CLUSTER = "cluster"
    const val PERFIL_FAIXAS = "perfil_faixas"
    const val PERFIL_CAMBIO = "perfil_cambio"
    const val CAMADA = "camada"
    const val GATILHO_NOME = "gatilho_nome"
    const val GATILHO_ENDERECO = "gatilho_endereco"
    const val FAVORITOS_GRAFICOS = "favoritos_graficos"
    const val RECORDE_060 = "recorde_060"
    const val RECORDE_0100 = "recorde_0100"
    const val ALERTAS = "alertas"
    const val PRESETS_GRAFICOS = "presets_graficos"
    const val CALIBRACAO_CONSUMO = "calibracao_consumo"
    const val HISTORICO_DESEMPENHO = "historico_desempenho"
    const val LITROS_TANQUE = "litros_tanque"
    const val OFFSET_TENSAO = "offset_tensao"
    const val HISTORICO_BATERIA = "historico_bateria"
    const val PLANO_MANUTENCAO = "plano_manutencao"
    const val ODOMETRO = "odometro"
    const val PIDS_CUSTOMIZADOS = "pids_customizados"
    const val PERFIL_MANUAL = "perfil_manual"

    /** Bolinha do Cluster sem escolha (padrão/automático). */
    private const val BOLINHA_PADRAO = "-"
    private const val SEPARADOR = ","

    /** Chaves próprias do perfil (sem as do Painel). */
    val CHAVES_PERFIL = listOf(
        SCHEMA, CLUSTER, PERFIL_FAIXAS, PERFIL_CAMBIO, CAMADA, GATILHO_NOME, GATILHO_ENDERECO,
        FAVORITOS_GRAFICOS, RECORDE_060, RECORDE_0100, ALERTAS, PRESETS_GRAFICOS,
        CALIBRACAO_CONSUMO, HISTORICO_DESEMPENHO, LITROS_TANQUE, OFFSET_TENSAO, HISTORICO_BATERIA,
        PLANO_MANUTENCAO, ODOMETRO, PIDS_CUSTOMIZADOS, PERFIL_MANUAL,
    )

    /** Campos do perfil (sem o Painel); null = chave removida. */
    fun codificarCampos(p: PerfilCarro): Map<String, String?> = mapOf(
        SCHEMA to VERSAO_SCHEMA.toString(),
        CLUSTER to p.cluster.joinToString(SEPARADOR) { it?.name ?: BOLINHA_PADRAO },
        PERFIL_FAIXAS to p.perfilFaixasId?.takeIf { it.isNotBlank() },
        PERFIL_CAMBIO to p.perfilCambioId?.takeIf { it.isNotBlank() },
        CAMADA to p.camada.name,
        GATILHO_NOME to p.gatilhoNome?.trim()?.takeIf { it.isNotEmpty() },
        GATILHO_ENDERECO to p.gatilhoEndereco?.takeIf { it.isNotBlank() },
        FAVORITOS_GRAFICOS to p.favoritosGraficos.takeIf { it.isNotEmpty() }?.joinToString(SEPARADOR),
        RECORDE_060 to p.recorde.melhor060?.toString(),
        RECORDE_0100 to p.recorde.melhor0100?.toString(),
        ALERTAS to p.alertas.codificar(),
        PRESETS_GRAFICOS to PresetsGrafico.codificar(p.presetsGraficos),
        CALIBRACAO_CONSUMO to p.calibracaoConsumo.codificar(),
        HISTORICO_DESEMPENHO to p.historicoDesempenho.codificar(),
        LITROS_TANQUE to p.litrosTanque.takeIf { it > 0 }?.toString(),
        OFFSET_TENSAO to p.offsetTensaoV.takeIf { it != 0.0 }?.toString(),
        HISTORICO_BATERIA to p.historicoBateria.codificar(),
        PLANO_MANUTENCAO to p.planoManutencao.codificar(),
        ODOMETRO to p.odometro?.codificar(),
        PIDS_CUSTOMIZADOS to PidCustomizado.codificar(p.pidsCustomizados),
        PERFIL_MANUAL to "true".takeIf { p.perfilManual },
    )

    /** Perfil completo (Painel + campos), pronto para o [com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor.gravar]. */
    fun codificar(p: PerfilCarro): Map<String, String?> = CodecPainel.codificar(p.painel) + codificarCampos(p)

    /** Todas as chaves que um perfil pode ocupar (para apagá-lo). */
    val TODAS_AS_CHAVES: Set<String> get() = codificar(PerfilCarro(0)).keys + CHAVES_PERFIL

    /** Lê o perfil de [veiculoId]; chaves ausentes ou inválidas voltam ao padrão (nunca lança). */
    fun decodificar(veiculoId: Long, ler: (String) -> String?): PerfilCarro = PerfilCarro(
        veiculoId = veiculoId,
        painel = CodecPainel.decodificar(ler),
        cluster = decodificarCluster(ler(CLUSTER)),
        perfilFaixasId = ler(PERFIL_FAIXAS)?.trim()?.takeIf { it.isNotEmpty() },
        perfilCambioId = ler(PERFIL_CAMBIO)?.trim()?.takeIf { it.isNotEmpty() },
        camada = CamadaPreferida.deCodigo(ler(CAMADA)),
        gatilhoNome = ler(GATILHO_NOME)?.trim()?.takeIf { it.isNotEmpty() },
        gatilhoEndereco = ler(GATILHO_ENDERECO)?.trim()?.takeIf { it.isNotEmpty() },
        favoritosGraficos = ler(FAVORITOS_GRAFICOS).orEmpty().split(SEPARADOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        recorde = RecordeDesempenho(tempo(ler(RECORDE_060)), tempo(ler(RECORDE_0100))),
        alertas = ConfigAlertas.decodificar(ler(ALERTAS)),
        presetsGraficos = PresetsGrafico.decodificar(ler(PRESETS_GRAFICOS)),
        calibracaoConsumo = CalibracaoConsumo.decodificar(ler(CALIBRACAO_CONSUMO)),
        historicoDesempenho = HistoricoDesempenho.decodificar(ler(HISTORICO_DESEMPENHO)),
        litrosTanque = ler(LITROS_TANQUE)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        offsetTensaoV = OffsetAdaptador.limitar(ler(OFFSET_TENSAO)?.toDoubleOrNull() ?: 0.0),
        historicoBateria = HistoricoBateria.decodificar(ler(HISTORICO_BATERIA)),
        planoManutencao = PlanoManutencao.decodificar(ler(PLANO_MANUTENCAO)),
        odometro = Odometro.decodificar(ler(ODOMETRO)),
        pidsCustomizados = PidCustomizado.decodificar(ler(PIDS_CUSTOMIZADOS)),
        perfilManual = ler(PERFIL_MANUAL) == "true",
    )

    /** Sempre [ClusterPainel.BOLINHAS] posições; nomes desconhecidos viram o padrão. */
    fun decodificarCluster(texto: String?): List<SensorPainel?> {
        val partes = texto.orEmpty().split(SEPARADOR).map { it.trim() }
        return List(ClusterPainel.BOLINHAS) { i -> partes.getOrNull(i)?.takeIf { it != BOLINHA_PADRAO }?.let(SensorPainel::deCodigo) }
    }

    /** Tempo de recorde válido (positivo e finito); qualquer outra coisa = sem recorde. */
    private fun tempo(texto: String?): Double? = texto?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}
