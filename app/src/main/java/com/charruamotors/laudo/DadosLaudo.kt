package com.charruamotors.laudo

/** De onde vieram os dados do laudo. Obrigatória: é ela que decide a faixa de modo simulado. */
enum class OrigemLaudo {
    /** Sessão em curso com o scanner Bluetooth conectado. */
    CONEXAO_REAL,

    /** Log salvo de uma sessão real. */
    LOG_REAL,

    /** Modo Simulado, log simulado ou geração fora de uma conexão real. */
    SIMULADO,
}

/** Identificação do veículo; todo campo null = não cadastrado ou não lido. */
data class VeiculoLaudo(
    /** Nome do perfil do carro (o veículo dono do perfil no `GerenciadorPerfisCarro`). */
    val perfil: String? = null,
    /** Perfil técnico em uso (faixas/motor), ex.: `OBD2 Genérico`. */
    val perfilTecnico: String? = null,
    val placa: String? = null,
    val modelo: String? = null,
    val vin: String? = null,
    /** Protocolo informado pelo adaptador (resolvido pelo `ATDPN`). */
    val protocolo: String? = null,
    val camada: String? = null,
    val adaptador: String? = null,
    val calid: String? = null,
    val combustivelEcu: String? = null,
    val tcm: String? = null,
)

data class DtcLaudo(val codigo: String, val descricao: String)

/** Quadro congelado (modo 02): o DTC que o disparou e as leituras já formatadas (rótulo → valor). */
data class FreezeFrameLaudo(val dtc: String?, val leituras: List<Pair<String, String>>)

data class MonitorLaudo(val nome: String, val pronto: Boolean)

/** Listas de DTCs: null = não lida / não suportada; vazia = lida sem códigos. */
data class DiagnosticoLaudo(
    /** Luz de injeção; null = estado não disponível. */
    val milAcesa: Boolean? = null,
    val ativos: List<DtcLaudo>? = null,
    val pendentes: List<DtcLaudo>? = null,
    val permanentes: List<DtcLaudo>? = null,
    val cambio: List<DtcLaudo>? = null,
    val freezeFrame: FreezeFrameLaudo? = null,
    /** Monitores de prontidão suportados; null = não lidos. */
    val monitores: List<MonitorLaudo>? = null,
)

/** Menor e maior valor lidos. */
data class FaixaLida(val minimo: Double, val maximo: Double)

/** Resumo técnico; null = sensor não suportado ou nunca lido. */
data class ResumoTecnicoLaudo(
    val tensao: FaixaLida? = null,
    val agua: FaixaLida? = null,
    val atf: FaixaLida? = null,
    /** Já sem o `010D = FF` espúrio. */
    val velocidadeMaximaKmh: Double? = null,
    val rpmMaximo: Double? = null,
    val duracaoSegundos: Double? = null,
    val distanciaKm: Double? = null,
    val segundosMovimento: Double? = null,
    val segundosParado: Double? = null,
    val velocidadeMediaKmh: Double? = null,
    val litros: Double? = null,
    val kmPorLitro: Double? = null,
)

/** v2.8.0: resumo do teste de bateria concluído na sessão, já formatado. */
data class TesteBateriaLaudo(
    val repouso: String,
    val partida: String,
    val alternador: String,
    val veredito: String,
    /** Motor considerado e ajuste do adaptador. */
    val condicoes: String,
)

/**
 * Instantâneo imutável de tudo o que o laudo mostra: montado de uma vez, antes de gerar o PDF em
 * segundo plano, sem referência à sessão OBD nem aos perfis.
 */
data class DadosLaudo(
    val origem: OrigemLaudo,
    val emitidoEmMs: Long,
    val oficina: String? = null,
    val tecnico: String? = null,
    /** `Sessão em curso` ou o nome do arquivo de log. */
    val fonte: String,
    val veiculo: VeiculoLaudo = VeiculoLaudo(),
    val diagnostico: DiagnosticoLaudo = DiagnosticoLaudo(),
    val resumo: ResumoTecnicoLaudo = ResumoTecnicoLaudo(),
    /** Alertas críticos disparados; null = a fonte não os registra. */
    val alertas: List<String>? = null,
    val observacoes: String? = null,
    /** v2.8.0: null = nenhum teste de bateria concluído na sessão. */
    val testeBateria: TesteBateriaLaudo? = null,
    /** v2.9.0: resumo curto (rótulo → texto) dos fuel trims e sondas da sessão; vazio = não analisados. */
    val injecao: List<Pair<String, String>> = emptyList(),
    /** v2.11.0: itens de manutenção vencidos ou próximos (nome → situação); vazio = sem a seção. */
    val manutencao: List<Pair<String, String>> = emptyList(),
) {
    val simulado: Boolean get() = origem == OrigemLaudo.SIMULADO
}
