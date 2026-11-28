package com.charruamotors.laudo

import com.charruamotors.domain.calculo.AcumuladorPercurso
import com.charruamotors.domain.calculo.FiltroVelocidadeEspuria
import com.charruamotors.obd.log.OrganizadorLogs
import com.charruamotors.obd.log.RelatorioSessaoTxt
import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.replay.LogCsv

/**
 * Laudo a partir de um log salvo. O relatório `.txt` da sessão traz veículo, DTCs, alertas e o
 * resumo; um CSV sem relatório (Data Logger, outro app) só permite o resumo calculado das colunas.
 * Nada do que a fonte não registrou é inventado. Puro (JVM).
 */
object LaudoDeLog {

    /**
     * Interpreta o relatório `.txt` do app (só o resumo: para no log bruto/eventos). Devolve null se
     * o arquivo não for um relatório do Charrua OBD Pro.
     */
    fun doRelatorio(nome: String, linhas: Sequence<String>, emitidoEmMs: Long): DadosLaudo? {
        val resumo = linhas
            .takeWhile { it != RelatorioSessaoTxt.TITULO_LOG_BRUTO && it != RelatorioSessaoTxt.TITULO_EVENTOS }
            .toList()
        if (resumo.none { "CHARRUA OBD PRO" in it }) return null

        /** Valor de `  Rótulo.....: valor`; textos de "não há" viram null. */
        fun campo(rotulo: String): String? = resumo.firstNotNullOfOrNull { linha ->
            if (!linha.startsWith("  $rotulo")) return@firstNotNullOfOrNull null
            REGEX_VALOR.matchEntire(linha.removePrefix("  $rotulo"))?.groupValues?.get(1)?.trim()
        }?.takeUnless { v -> SEM_VALOR.any { v.startsWith(it) } }

        /** Linhas de uma seção, até a linha em branco que a encerra. */
        fun secao(titulo: String): List<String> =
            resumo.dropWhile { !it.startsWith(titulo) }.drop(1).takeWhile { it.isNotBlank() }

        val secaoDtcs = secao("CÓDIGOS DE FALHA")
        fun dtcs(rotulo: String): List<DtcLaudo>? {
            val inicio = secaoDtcs.indexOfFirst { it.startsWith("  $rotulo") }
            if (inicio < 0) return null
            val situacao = secaoDtcs[inicio].substringAfterLast(": ")
            if (situacao.startsWith("não")) return null
            return secaoDtcs.drop(inicio + 1).takeWhile { it.startsWith("    •") }.map {
                val item = it.removePrefix("    •").trim()
                DtcLaudo(item.substringBefore(" — "), item.substringAfter(" — ", ""))
            }
        }

        val sensores = secao(RelatorioSessaoTxt.TITULO_SENSORES).mapNotNull { linha ->
            val m = REGEX_SENSOR.find(linha) ?: return@mapNotNull null
            val minimo = numero(m.groupValues[2]) ?: return@mapNotNull null
            val maximo = numero(m.groupValues[3]) ?: return@mapNotNull null
            m.groupValues[1] to FaixaLida(minimo, maximo)
        }.toMap()

        val alertas = secao("ALERTAS DA SESSÃO").filter { it.startsWith("    •") }.map { it.removePrefix("    •").trim() }
        val simulado = OrganizadorLogs.ehSimulado(nome) || campo("Modo") == "SIMULADO"
        return DadosLaudo(
            origem = if (simulado) OrigemLaudo.SIMULADO else OrigemLaudo.LOG_REAL,
            emitidoEmMs = emitidoEmMs,
            fonte = nome + campo("Início")?.let { " (sessão de $it)" }.orEmpty(),
            veiculo = VeiculoLaudo(
                perfil = campo("Veículo"),
                perfilTecnico = campo("Perfil de faixas"),
                placa = campo("Placa"),
                vin = campo("VIN (0902)"),
                protocolo = campo("Protocolo"),
                camada = campo("Camada"),
                calid = campo("CALID (0904)"),
                combustivelEcu = campo("Combustível (0151)"),
                tcm = campo("TCM detectado"),
            ),
            diagnostico = DiagnosticoLaudo(
                ativos = dtcs("Confirmados"),
                pendentes = dtcs("Pendentes"),
                permanentes = dtcs("Permanentes"),
            ),
            resumo = ResumoTecnicoLaudo(
                tensao = sensores[Pids.TENSAO_BATERIA],
                agua = sensores[Pids.TEMPERATURA_MOTOR],
                atf = Pids.COMANDOS_ATF.firstNotNullOfOrNull { sensores[it] },
                velocidadeMaximaKmh = primeiroNumero(campo("Velocidade máxima"))
                    ?.takeIf { it < FiltroVelocidadeEspuria.VALOR_ESPURIO_KMH },
                rpmMaximo = primeiroNumero(campo("RPM máximo")),
                duracaoSegundos = campo("Duração")?.let(::segundos),
                distanciaKm = primeiroNumero(campo("Distância percorrida")),
                litros = primeiroNumero(campo("Combustível estimado (L)")),
                kmPorLitro = primeiroNumero(campo("Consumo médio")),
                map = sensores[Pids.PRESSAO_COLETOR],
                rail = sensores[Pids.PRESSAO_RAIL],
            ),
            alertas = alertas,
        )
    }

    /**
     * Laudo de uma sessão com os dois arquivos: o relatório `.txt` manda (veículo, DTCs, alertas) e o
     * CSV completa o que ele não registrou no resumo (tempos em movimento/parado, sensores). Com um
     * lado só, devolve esse lado; null sem nenhum.
     */
    fun daSessao(doTxt: DadosLaudo?, doCsv: DadosLaudo?): DadosLaudo? {
        if (doTxt == null) return doCsv
        if (doCsv == null) return doTxt
        val a = doTxt.resumo
        val b = doCsv.resumo
        val simulado = doTxt.simulado || doCsv.simulado
        return doTxt.copy(
            origem = if (simulado) OrigemLaudo.SIMULADO else doTxt.origem,
            fonte = "${doTxt.fonte} + ${doCsv.fonte}",
            resumo = ResumoTecnicoLaudo(
                tensao = a.tensao ?: b.tensao,
                agua = a.agua ?: b.agua,
                atf = a.atf ?: b.atf,
                velocidadeMaximaKmh = a.velocidadeMaximaKmh ?: b.velocidadeMaximaKmh,
                rpmMaximo = a.rpmMaximo ?: b.rpmMaximo,
                duracaoSegundos = a.duracaoSegundos ?: b.duracaoSegundos,
                distanciaKm = a.distanciaKm ?: b.distanciaKm,
                segundosMovimento = a.segundosMovimento ?: b.segundosMovimento,
                segundosParado = a.segundosParado ?: b.segundosParado,
                velocidadeMediaKmh = a.velocidadeMediaKmh ?: b.velocidadeMediaKmh,
                litros = a.litros ?: b.litros,
                kmPorLitro = a.kmPorLitro ?: b.kmPorLitro,
                map = a.map ?: b.map,
                rail = a.rail ?: b.rail,
            ),
        )
    }

    /** Resumo calculado das colunas do CSV, identificadas pelo comando do cabeçalho. */
    fun doCsv(nome: String, log: LogCsv, emitidoEmMs: Long): DadosLaudo {
        fun serie(comando: String): DoubleArray? =
            log.colunas.indexOfFirst { it.comando == comando }.takeIf { it >= 0 }?.let { log.series[it] }

        fun faixa(comando: String): FaixaLida? {
            val validos = serie(comando)?.filter { it.isFinite() }.orEmpty()
            return if (validos.isEmpty()) null else FaixaLida(validos.min(), validos.max())
        }

        val velocidade = serie(Pids.VELOCIDADE)
        val rpm = serie(Pids.RPM)
        val percurso = AcumuladorPercurso()
        for (i in 0 until log.amostras) {
            percurso.registrar(velocidade?.get(i), rpm?.get(i), null, log.instantesMs[i])
        }
        val e = percurso.estatisticas
        return DadosLaudo(
            origem = if (OrganizadorLogs.ehSimulado(nome)) OrigemLaudo.SIMULADO else OrigemLaudo.LOG_REAL,
            emitidoEmMs = emitidoEmMs,
            fonte = nome,
            resumo = ResumoTecnicoLaudo(
                tensao = faixa(Pids.TENSAO_BATERIA),
                agua = faixa(Pids.TEMPERATURA_MOTOR),
                atf = Pids.COMANDOS_ATF.firstNotNullOfOrNull(::faixa),
                velocidadeMaximaKmh = e.velocidadeMaximaKmh,
                rpmMaximo = e.rpmMaximo,
                duracaoSegundos = (log.fimMs - log.inicioMs) / 1000.0,
                distanciaKm = e.distanciaKm.takeIf { velocidade != null },
                segundosMovimento = e.segundosMovimento.takeIf { velocidade != null },
                segundosParado = e.segundosParado.takeIf { velocidade != null },
                velocidadeMediaKmh = e.velocidadeMediaKmh,
                map = faixa(Pids.PRESSAO_COLETOR),
                rail = faixa(Pids.PRESSAO_RAIL),
            ),
        )
    }

    private val REGEX_VALOR = Regex("""\.*: (.*)""")
    private val REGEX_SENSOR = Regex("""\[([^\]]+)]: mín (\S+) · máx (\S+) ·""")
    private val REGEX_NUMERO = Regex("""-?\d[\d.]*(,\d+)?""")
    private val REGEX_DURACAO = Regex("""(\d+) (h|min|s)\b""")
    private val SEM_VALOR = listOf("—", "não lido", "não sondado", "sem resposta", "não vinculado")

    /** Número no formato brasileiro (`3.420`, `12,6`). */
    private fun numero(texto: String): Double? = texto.replace(".", "").replace(',', '.').toDoubleOrNull()

    private fun primeiroNumero(texto: String?): Double? = texto?.let { REGEX_NUMERO.find(it) }?.let { numero(it.value) }

    /** `1 h 02 min 05 s` → segundos. */
    private fun segundos(texto: String): Double = REGEX_DURACAO.findAll(texto).sumOf {
        val fator = when (it.groupValues[2]) {
            "h" -> 3600
            "min" -> 60
            else -> 1
        }
        it.groupValues[1].toDouble() * fator
    }
}
