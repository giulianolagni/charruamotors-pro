package com.charruamotors.direcao.desempenho

import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Testes da tela Testes de Aceleração. Nos de arrancada ([partidaParada]) o tempo começa na primeira
 * velocidade acima de 0; nos de retomada, no instante em que a velocidade cruza [inicioKmh].
 */
enum class ModalidadeTeste(
    /** Gravado no histórico do veículo: não renomear. */
    val codigo: String,
    val titulo: String,
    val inicioKmh: Double,
    /** Velocidade de chegada; null nos testes por distância. */
    val alvoKmh: Double?,
    /** Distância de chegada em metros; null nos testes por velocidade. */
    val distanciaM: Double?,
) {
    V0_60("0_60", "0–60 km/h", 0.0, 60.0, null),
    V0_100("0_100", "0–100 km/h", 0.0, 100.0, null),
    V60_100("60_100", "60–100 km/h", 60.0, 100.0, null),
    V80_120("80_120", "80–120 km/h", 80.0, 120.0, null),
    D200("200m", "200 m", 0.0, null, 200.0),
    D400("400m", "400 m", 0.0, null, 400.0),
    ;

    val partidaParada: Boolean get() = inicioKmh == 0.0

    companion object {
        fun deCodigo(codigo: String?): ModalidadeTeste? = entries.firstOrNull { it.codigo.equals(codigo?.trim(), ignoreCase = true) }
    }
}

/** Máquina de estados do teste. */
enum class FaseTeste {
    DESARMADO,

    /** Arrancada: esperando 0 km/h. Retomada: esperando ficar abaixo da velocidade inicial. */
    AGUARDANDO_PARADA,
    ARMADO,
    EM_MEDICAO,
    CONCLUIDO,
    CANCELADO,
}

enum class MotivoCancelamento(val texto: String) {
    USUARIO("Cancelado"),
    SAIU_DA_TELA("Cancelado ao sair da tela"),
    DESCONECTADO("Conexão com o scanner encerrada"),
    FERRAMENTA_MANUAL("Cancelado: uma ferramenta manual assumiu o scanner"),
    DESACELEROU("Desacelerou antes da chegada"),
    TEMPO_MAXIMO("Passou do tempo máximo de medição"),
    SEM_LEITURAS("Intervalo grande sem leitura de velocidade: tempo descartado"),
    ARMADO_DEMAIS("Armado por muito tempo sem arrancar"),
}

/** Chegada de um teste: tempo interpolado e a velocidade no instante da chegada. */
data class ResultadoTeste(val modalidade: ModalidadeTeste, val tempoMs: Long, val velocidadeFinalKmh: Double)

data class EstadoTeste(
    val modalidade: ModalidadeTeste = ModalidadeTeste.V0_100,
    val fase: FaseTeste = FaseTeste.DESARMADO,
    /** Tempo corrido desde a saída, durante a medição. */
    val decorridoMs: Long = 0,
    /** Última velocidade aceita. */
    val velocidadeKmh: Double? = null,
    /** Metros integrados desde a saída (todos os testes; decide a chegada nos de distância). */
    val distanciaM: Double = 0.0,
    val resultado: ResultadoTeste? = null,
    val motivo: MotivoCancelamento? = null,
) {
    /** Há um teste em andamento (o botão vira "Cancelar")? */
    val ativo: Boolean
        get() = fase == FaseTeste.AGUARDANDO_PARADA || fase == FaseTeste.ARMADO || fase == FaseTeste.EM_MEDICAO
}

/**
 * Cronômetro de aceleração (puro, JVM): *Aguardando parada → Armado → Em medição → Concluído*,
 * alimentado pelas leituras **frescas** de velocidade com o instante de cada uma ([amostrar]).
 *
 * - A chegada (velocidade ou distância) é **interpolada** entre as duas amostras que a cruzam, para o
 *   tempo não depender da latência do ELM327. Nas retomadas, a saída também é interpolada.
 * - [VELOCIDADE_ESPURIA_KMH] (`010D = FF`), valores negativos e não finitos são ignorados por
 *   inteiro: não disparam, não concluem e não viram referência da interpolação.
 * - Um intervalo maior que [MAX_LACUNA_MS] sem leitura invalida a medição.
 * - O armar sempre passa por *Aguardando parada*: só uma leitura nova arma o teste.
 *
 * Não é thread-safe: ver [ControladorTesteAceleracao].
 */
class CronometroAceleracao {

    var estado: EstadoTeste = EstadoTeste()
        private set

    private class Amostra(val instanteMs: Long, val kmh: Double)

    private var anterior: Amostra? = null
    private var inicioMs = 0.0
    private var distanciaM = 0.0
    private var pico = 0.0

    /** Quando o teste ficou armado (para o limite [MAX_ARMADO_MS]). */
    private var armadoDesdeMs = 0L

    fun armar(modalidade: ModalidadeTeste): EstadoTeste {
        anterior = null
        distanciaM = 0.0
        estado = EstadoTeste(modalidade, FaseTeste.AGUARDANDO_PARADA)
        return estado
    }

    /** Encerra o teste em andamento; sem teste ativo, o resultado que está na tela fica como está. */
    fun cancelar(motivo: MotivoCancelamento): EstadoTeste {
        if (!estado.ativo) return estado
        anterior = null
        estado = estado.copy(fase = FaseTeste.CANCELADO, motivo = motivo)
        return estado
    }

    /** Volta ao estado inicial (descarta o resultado na tela). */
    fun desarmar(modalidade: ModalidadeTeste = estado.modalidade): EstadoTeste {
        anterior = null
        estado = EstadoTeste(modalidade)
        return estado
    }

    fun amostrar(velocidadeKmh: Double, instanteMs: Long): EstadoTeste {
        if (!estado.ativo || !valida(velocidadeKmh)) return estado
        val antes = anterior
        if (antes != null && instanteMs <= antes.instanteMs) return estado
        val agora = Amostra(instanteMs, velocidadeKmh)
        anterior = agora
        when (estado.fase) {
            FaseTeste.AGUARDANDO_PARADA -> {
                if (prontoParaSair(velocidadeKmh)) armado(agora) else estado = estado.copy(velocidadeKmh = velocidadeKmh)
            }
            FaseTeste.ARMADO -> disparar(antes, agora)
            FaseTeste.EM_MEDICAO -> medir(antes ?: agora, agora)
            else -> Unit
        }
        return estado
    }

    /** Passagem do tempo sem amostra: leituras paradas, tempo máximo ou teste esquecido armado. */
    fun tique(agoraMs: Long): EstadoTeste {
        when (estado.fase) {
            FaseTeste.ARMADO -> if (agoraMs - armadoDesdeMs > MAX_ARMADO_MS) cancelar(MotivoCancelamento.ARMADO_DEMAIS)
            FaseTeste.EM_MEDICAO -> {
                val ultima = anterior?.instanteMs ?: agoraMs
                if (agoraMs - ultima > MAX_LACUNA_MS) cancelar(MotivoCancelamento.SEM_LEITURAS)
                else if (agoraMs - inicioMs > MAX_MEDICAO_MS) cancelar(MotivoCancelamento.TEMPO_MAXIMO)
            }
            else -> Unit
        }
        return estado
    }

    private fun valida(v: Double) = v.isFinite() && v >= 0.0 && v < VELOCIDADE_ESPURIA_KMH

    private fun parado(v: Double) = v < LIMITE_PARADO_KMH

    private fun prontoParaSair(v: Double) =
        if (estado.modalidade.partidaParada) parado(v) else v < estado.modalidade.inicioKmh

    private fun armado(agora: Amostra) {
        armadoDesdeMs = agora.instanteMs
        distanciaM = 0.0
        estado = EstadoTeste(estado.modalidade, FaseTeste.ARMADO, velocidadeKmh = agora.kmh)
    }

    private fun aguardar(agora: Amostra) {
        distanciaM = 0.0
        estado = EstadoTeste(estado.modalidade, FaseTeste.AGUARDANDO_PARADA, velocidadeKmh = agora.kmh)
    }

    private fun disparar(antes: Amostra?, agora: Amostra) {
        val m = estado.modalidade
        if (prontoParaSair(agora.kmh)) {
            estado = estado.copy(velocidadeKmh = agora.kmh)
            return
        }
        // Buraco nas leituras ou chegada já ultrapassada: a saída não pode ser medida.
        val semReferencia = antes == null || agora.instanteMs - antes.instanteMs > MAX_LACUNA_MS
        if (semReferencia || (m.alvoKmh != null && agora.kmh >= m.alvoKmh)) {
            aguardar(agora)
            return
        }
        distanciaM = 0.0
        pico = agora.kmh
        inicioMs = if (m.partidaParada) {
            agora.instanteMs.toDouble()
        } else {
            val de = antes!!
            val saida = interpolar(de, agora, m.inicioKmh)
            // O trecho entre o cruzamento da velocidade inicial e esta amostra já conta na distância.
            distanciaM = metros(m.inicioKmh, agora.kmh, (agora.instanteMs - saida) / 1000.0)
            saida
        }
        estado = EstadoTeste(
            m,
            FaseTeste.EM_MEDICAO,
            decorridoMs = (agora.instanteMs - inicioMs).roundToLong(),
            velocidadeKmh = agora.kmh,
            distanciaM = distanciaM,
        )
    }

    private fun medir(antes: Amostra, agora: Amostra) {
        val m = estado.modalidade
        if (agora.instanteMs - antes.instanteMs > MAX_LACUNA_MS) {
            cancelar(MotivoCancelamento.SEM_LEITURAS)
            return
        }
        // Parou antes da chegada numa arrancada: nova tentativa, já parado.
        if (m.partidaParada && parado(agora.kmh)) {
            armado(agora)
            return
        }
        if (agora.kmh < pico - TOLERANCIA_RECUO_KMH) {
            cancelar(MotivoCancelamento.DESACELEROU)
            return
        }
        val trecho = metros(antes.kmh, agora.kmh, (agora.instanteMs - antes.instanteMs) / 1000.0)
        val chegada: Pair<Double, Double>? = when {
            m.alvoKmh != null && agora.kmh >= m.alvoKmh -> interpolar(antes, agora, m.alvoKmh) to m.alvoKmh
            m.distanciaM != null && distanciaM + trecho >= m.distanciaM -> cruzarDistancia(antes, agora, m.distanciaM - distanciaM)
            else -> null
        }
        if (chegada != null) {
            val (instante, velocidade) = chegada
            if (instante - inicioMs > MAX_MEDICAO_MS) {
                cancelar(MotivoCancelamento.TEMPO_MAXIMO)
                return
            }
            val tempo = (instante - inicioMs).roundToLong().coerceAtLeast(0)
            val percorrido = m.distanciaM
                ?: (distanciaM + metros(antes.kmh, velocidade, (instante - antes.instanteMs).coerceAtLeast(0.0) / 1000.0))
            anterior = null
            estado = EstadoTeste(
                m,
                FaseTeste.CONCLUIDO,
                decorridoMs = tempo,
                velocidadeKmh = agora.kmh,
                distanciaM = percorrido,
                resultado = ResultadoTeste(m, tempo, velocidade),
            )
            return
        }
        if (agora.instanteMs - inicioMs > MAX_MEDICAO_MS) {
            cancelar(MotivoCancelamento.TEMPO_MAXIMO)
            return
        }
        distanciaM += trecho
        pico = maxOf(pico, agora.kmh)
        estado = estado.copy(
            decorridoMs = (agora.instanteMs - inicioMs).roundToLong(),
            velocidadeKmh = agora.kmh,
            distanciaM = distanciaM,
        )
    }

    /** Instante em que a velocidade cruzou [alvo] entre as duas amostras. */
    private fun interpolar(de: Amostra, ate: Amostra, alvo: Double): Double {
        if (ate.kmh <= de.kmh || de.kmh >= alvo) return ate.instanteMs.toDouble()
        val fracao = (alvo - de.kmh) / (ate.kmh - de.kmh)
        return de.instanteMs + fracao * (ate.instanteMs - de.instanteMs)
    }

    /** Metros percorridos em [segundos] com a velocidade variando linearmente. */
    private fun metros(deKmh: Double, ateKmh: Double, segundos: Double): Double = (deKmh + ateKmh) / 2.0 / 3.6 * segundos

    /**
     * Instante e velocidade em que faltavam [restanteM] metros dentro do intervalo: resolve
     * `restante = u·t + a·t²/2` com a aceleração constante entre as duas amostras.
     */
    private fun cruzarDistancia(de: Amostra, ate: Amostra, restanteM: Double): Pair<Double, Double> {
        val duracao = (ate.instanteMs - de.instanteMs) / 1000.0
        val u = de.kmh / 3.6
        val a = (ate.kmh / 3.6 - u) / duracao
        val t = if (kotlin.math.abs(a) < 1e-9) {
            restanteM / u
        } else {
            (sqrt((u * u + 2 * a * restanteM).coerceAtLeast(0.0)) - u) / a
        }.coerceIn(0.0, duracao)
        return (de.instanteMs + t * 1000.0) to (u + a * t) * 3.6
    }

    companion object {
        /** `010D = FF`: saturação do PID, nunca uma leitura válida num teste de aceleração. */
        const val VELOCIDADE_ESPURIA_KMH = 255.0

        /** O PID 010D tem resolução de 1 km/h: abaixo de 0,5 é "0". */
        const val LIMITE_PARADO_KMH = 0.5

        /** Recuo aceitável abaixo do pico (troca de marcha) antes de cancelar. */
        const val TOLERANCIA_RECUO_KMH = 4.0

        const val MAX_LACUNA_MS = 3_000L
        const val MAX_MEDICAO_MS = 60_000L

        /** Armado (com o polling focado) por mais que isto é esquecimento. */
        const val MAX_ARMADO_MS = 3 * 60_000L
    }
}
