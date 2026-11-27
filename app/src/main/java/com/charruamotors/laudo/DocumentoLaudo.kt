package com.charruamotors.laudo

import com.charruamotors.obd.log.RelatorioSessaoTxt
import java.time.ZoneId

/** Um trecho do corpo do laudo, na ordem de leitura. */
sealed interface BlocoLaudo {
    data class Secao(val titulo: String) : BlocoLaudo
    data class Subtitulo(val texto: String) : BlocoLaudo
    data class Campo(val rotulo: String, val valor: String) : BlocoLaudo
    data class Item(val texto: String) : BlocoLaudo
    data class Paragrafo(val texto: String) : BlocoLaudo
}

/** O laudo já formatado em PT-BR: cabeçalho, faixa de destaque (ou null) e corpo. */
data class DocumentoLaudo(
    val titulo: String,
    val cabecalho: List<String>,
    /** Repetida em **todas** as páginas. */
    val faixaDestaque: String?,
    val blocos: List<BlocoLaudo>,
)

/** Transforma os [DadosLaudo] no texto do laudo. Puro (JVM). */
object MontadorLaudo {

    const val TITULO = "Charrua OBD Pro"
    const val FAIXA_SIMULADO = "MODO SIMULADO — DADOS FICTÍCIOS"
    const val NAO_INFORMADO = "não informado"
    const val SEM_LEITURA = "não suportado / sem leitura"

    fun montar(d: DadosLaudo, zona: ZoneId = ZoneId.systemDefault()): DocumentoLaudo = DocumentoLaudo(
        titulo = TITULO,
        cabecalho = buildList {
            add("Laudo Técnico de Oficina")
            d.oficina.preenchido()?.let { add("Oficina: $it") }
            d.tecnico.preenchido()?.let { add("Técnico: $it") }
            add("Emitido em ${RelatorioSessaoTxt.dataHora(d.emitidoEmMs, zona)} · Fonte: ${d.fonte}")
        },
        faixaDestaque = FAIXA_SIMULADO.takeIf { d.simulado },
        blocos = buildList {
            veiculo(d.veiculo)
            diagnostico(d.diagnostico)
            resumo(d.resumo)
            d.testeBateria?.let { testeBateria(it) }
            alertas(d.alertas)
            d.observacoes.preenchido()?.let {
                add(BlocoLaudo.Secao("Observações do técnico"))
                add(BlocoLaudo.Paragrafo(it))
            }
        },
    )

    private fun MutableList<BlocoLaudo>.veiculo(v: VeiculoLaudo) {
        add(BlocoLaudo.Secao("Identificação do veículo"))
        fun campo(rotulo: String, valor: String?) = add(BlocoLaudo.Campo(rotulo, valor.preenchido() ?: NAO_INFORMADO))
        fun opcional(rotulo: String, valor: String?) {
            valor.preenchido()?.let { add(BlocoLaudo.Campo(rotulo, it)) }
        }
        campo("Perfil do carro", v.perfil)
        opcional("Perfil técnico", v.perfilTecnico)
        opcional("Modelo", v.modelo)
        opcional("Placa", v.placa)
        campo("VIN (chassi)", v.vin)
        campo("Protocolo (ATDPN)", v.protocolo)
        campo(
            "ECU",
            listOfNotNull(
                v.camada.preenchido(),
                v.calid.preenchido()?.let { "CALID $it" },
                v.combustivelEcu.preenchido()?.let { "combustível $it" },
                v.tcm.preenchido()?.let { "TCM $it" },
            ).joinToString(" · "),
        )
        opcional("Adaptador", v.adaptador)
    }

    private fun MutableList<BlocoLaudo>.diagnostico(d: DiagnosticoLaudo) {
        add(BlocoLaudo.Secao("Diagnóstico"))
        val mil = when (d.milAcesa) {
            true -> "ACESA"
            false -> "apagada"
            null -> "estado não disponível"
        }
        add(BlocoLaudo.Campo("Luz de injeção (MIL)", mil))
        codigos("Falhas ativas / confirmadas", d.ativos)
        codigos("Falhas pendentes", d.pendentes)
        d.permanentes?.takeIf { it.isNotEmpty() }?.let { codigos("Falhas permanentes", it) }
        d.cambio?.takeIf { it.isNotEmpty() }?.let { codigos("Falhas do câmbio (TCM)", it) }
        d.freezeFrame?.let { ff ->
            add(BlocoLaudo.Subtitulo("Freeze Frame (Modo 02)" + ff.dtc?.let { " — $it" }.orEmpty()))
            ff.leituras.forEach { (rotulo, valor) -> add(BlocoLaudo.Campo(rotulo, valor)) }
        }
        add(BlocoLaudo.Subtitulo("Monitores de prontidão"))
        val monitores = d.monitores
        if (monitores.isNullOrEmpty()) {
            add(BlocoLaudo.Paragrafo("Não disponíveis nesta fonte de dados."))
        } else {
            val prontos = monitores.count { it.pronto }
            add(BlocoLaudo.Paragrafo("$prontos pronto(s) e ${monitores.size - prontos} pendente(s) de ${monitores.size} suportado(s)."))
            monitores.forEach { add(BlocoLaudo.Item("${it.nome}: ${if (it.pronto) "pronto" else "pendente"}")) }
        }
    }

    private fun MutableList<BlocoLaudo>.codigos(titulo: String, lista: List<DtcLaudo>?) {
        add(BlocoLaudo.Subtitulo(if (lista == null) titulo else "$titulo (${lista.size})"))
        when {
            lista == null -> add(BlocoLaudo.Paragrafo("Não lidas / não suportadas."))
            lista.isEmpty() -> add(BlocoLaudo.Paragrafo("Nenhum código."))
            else -> lista.forEach { add(BlocoLaudo.Item("${it.codigo} — ${it.descricao}")) }
        }
    }

    private fun MutableList<BlocoLaudo>.resumo(r: ResumoTecnicoLaudo) {
        add(BlocoLaudo.Secao("Resumo técnico da sessão"))
        fun faixa(rotulo: String, f: FaixaLida?, casas: Int, unidade: String) = add(
            BlocoLaudo.Campo(
                rotulo,
                f?.let { "mín ${numero(it.minimo, casas)} $unidade · máx ${numero(it.maximo, casas)} $unidade" } ?: SEM_LEITURA,
            ),
        )
        faixa("Bateria / alternador", r.tensao, 1, "V")
        faixa("Temperatura da água", r.agua, 0, "°C")
        faixa("Temperatura do ATF", r.atf, 0, "°C")
        add(BlocoLaudo.Campo("Velocidade máxima", r.velocidadeMaximaKmh?.let { "${numero(it, 0)} km/h" } ?: SEM_LEITURA))
        add(BlocoLaudo.Campo("RPM máximo", r.rpmMaximo?.let { "${numero(it, 0)} rpm" } ?: SEM_LEITURA))

        // Estatísticas da viagem: só o que a fonte tiver.
        fun viagem(rotulo: String, valor: String?) {
            if (valor != null) add(BlocoLaudo.Campo(rotulo, valor))
        }
        fun tempo(segundos: Double?) = segundos?.takeIf { it > 0 }?.let { RelatorioSessaoTxt.duracao((it * 1000).toLong()) }
        viagem("Duração", tempo(r.duracaoSegundos))
        viagem("Distância percorrida", r.distanciaKm?.takeIf { it > 0 }?.let { "${numero(it, 1)} km" })
        viagem("Tempo em movimento", tempo(r.segundosMovimento))
        viagem("Tempo parado", tempo(r.segundosParado))
        viagem("Velocidade média", r.velocidadeMediaKmh?.takeIf { it > 0 }?.let { "${numero(it, 1)} km/h" })
        viagem("Combustível estimado", r.litros?.takeIf { it > 0 }?.let { "${numero(it, 2)} L" })
        viagem("Consumo médio", r.kmPorLitro?.let { "${numero(it, 1)} km/L" })
    }

    private fun MutableList<BlocoLaudo>.testeBateria(t: TesteBateriaLaudo) {
        add(BlocoLaudo.Secao("Teste de bateria e alternador"))
        add(BlocoLaudo.Campo("Repouso", t.repouso))
        add(BlocoLaudo.Campo("Mínimo na partida", t.partida))
        add(BlocoLaudo.Campo("Alternador", t.alternador))
        add(BlocoLaudo.Campo("Veredito", t.veredito))
        add(BlocoLaudo.Campo("Condições", t.condicoes))
    }

    private fun MutableList<BlocoLaudo>.alertas(alertas: List<String>?) {
        add(BlocoLaudo.Secao("Alertas críticos"))
        when {
            alertas == null -> add(BlocoLaudo.Paragrafo("Esta fonte de dados não registra alertas."))
            alertas.isEmpty() -> add(BlocoLaudo.Paragrafo("Nenhum alerta disparado."))
            else -> alertas.forEach { add(BlocoLaudo.Item(it)) }
        }
    }

    private fun numero(valor: Double, casas: Int) = RelatorioSessaoTxt.numero(valor, casas)

    private fun String?.preenchido(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
