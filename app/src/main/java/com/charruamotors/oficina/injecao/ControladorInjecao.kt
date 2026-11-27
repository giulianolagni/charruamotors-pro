package com.charruamotors.oficina.injecao

import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Os PIDs de um banco que o carro suporta. */
data class SuporteBanco(val stft: String?, val ltft: String?) {
    val suportado: Boolean get() = stft != null || ltft != null
}

/** O que o Diagnóstico de Injeção consegue ler neste carro. */
data class SuporteInjecao(
    val banco1: SuporteBanco = SuporteBanco(null, null),
    val banco2: SuporteBanco = SuporteBanco(null, null),
    /** `0124`/`0134` (banda larga) ou `0114` (tensão). */
    val sondaPre: String? = null,
    /** `0115`. */
    val sondaPos: String? = null,
    private val apoio: Set<String> = emptySet(),
) {
    val tipoSondaPre: TipoSondaPre?
        get() = sondaPre?.let { if (it == Pids.SONDA_LAMBDA_TENSAO) TipoSondaPre.ESTREITA else TipoSondaPre.BANDA_LARGA }

    /** PIDs a priorizar no polling; vazio se não há trim nem sonda (nada a pedir à ECU). */
    val comandos: Set<String>
        get() {
            val principais = listOfNotNull(banco1.stft, banco1.ltft, banco2.stft, banco2.ltft, sondaPre, sondaPos)
            return if (principais.isEmpty()) emptySet() else (principais + apoio).toSet()
        }

    companion object {
        /** Água e carga: separam os regimes e liberam a análise das sondas. */
        private val APOIO = listOf(Pids.TEMPERATURA_MOTOR, Pids.CARGA_MOTOR)

        /**
         * Só o que a ECU anunciou. Nos modos 21/22 (SW4) nada do Modo 01 é lido: tudo aparece como não
         * suportado e a sequência do polling não muda.
         */
        fun de(suportados: Set<String>, modo: ModoComunicacaoEcu): SuporteInjecao {
            if (modo != ModoComunicacaoEcu.OBD2_PADRAO) return SuporteInjecao()
            fun se(comando: String) = comando.takeIf { it in suportados }
            return SuporteInjecao(
                banco1 = SuporteBanco(se(Pids.STFT), se(Pids.LTFT)),
                banco2 = SuporteBanco(se(Pids.STFT_B2), se(Pids.LTFT_B2)),
                // O 0115 não é pré-catalisador: sem sonda anterior, não há o que analisar nele sozinho.
                sondaPre = (Pids.COMANDOS_LAMBDA_MEDIDO + Pids.SONDA_LAMBDA_TENSAO).firstOrNull { it in suportados },
                sondaPos = se(Pids.SONDA_LAMBDA_POS),
                apoio = APOIO.filterTo(linkedSetOf()) { it in suportados },
            )
        }
    }
}

/** Leituras e médias de um banco. */
data class LeituraBanco(
    val stft: Double? = null,
    val ltft: Double? = null,
    val total: Double? = null,
    val lenta: MediaTrim? = null,
    val carga: MediaTrim? = null,
    val amostrasLenta: Int = 0,
    val amostrasCarga: Int = 0,
) {
    val dica: String get() = AnaliseMistura.dica(lenta?.media, carga?.media)
}

/** Um ponto das curvas S1 × S2 (null = sonda ainda não lida). */
data class PontoSondas(val instanteMs: Long, val pre: Double?, val pos: Double?)

data class EstadoInjecao(
    val suporte: SuporteInjecao = SuporteInjecao(),
    val banco1: LeituraBanco = LeituraBanco(),
    val banco2: LeituraBanco = LeituraBanco(),
    val regime: RegimeMotor? = null,
    val agua: Double? = null,
    val sondaPre: Double? = null,
    val sondaPos: Double? = null,
    val sondas: DiagnosticoSondas = DiagnosticoSondas(),
    val curva: List<PontoSondas> = emptyList(),
)

/** O que foi medido na sessão, para o laudo. */
data class ResumoInjecao(
    val banco1: LeituraBanco?,
    val banco2: LeituraBanco?,
    /** Última análise conclusiva das sondas. */
    val sondas: DiagnosticoSondas?,
)

/**
 * Análise de mistura e sondas da sessão OBD em curso: só acumula com a tela aberta ([abrir]/[fechar]) e
 * zera a cada sessão ([novaSessao]), sem nada gravado em disco. Seguro entre threads.
 */
class ControladorInjecao(private val relogioMs: () -> Long) {

    private val trava = Any()
    private var aberta = false
    private var suporte = SuporteInjecao()
    private var diesel = false
    private val banco1 = AcumuladorTrim()
    private val banco2 = AcumuladorTrim()
    private var analise = AnaliseSondas(null, temPos = false, diesel = false)
    private var conclusao: DiagnosticoSondas? = null
    private val curva = ArrayDeque<PontoSondas>()

    private val _estado = MutableStateFlow(EstadoInjecao())
    val estado: StateFlow<EstadoInjecao> = _estado.asStateFlow()

    /** Tela aberta. Chamar de novo quando o suporte mudar; as médias ficam. */
    fun abrir(novo: SuporteInjecao, motorDiesel: Boolean) = synchronized(trava) {
        if (novo.sondaPre != suporte.sondaPre || novo.sondaPos != suporte.sondaPos || motorDiesel != diesel) {
            analise = AnaliseSondas(novo.tipoSondaPre, novo.sondaPos != null, motorDiesel)
            curva.clear()
        }
        suporte = novo
        diesel = motorDiesel
        aberta = true
        _estado.value = _estado.value.copy(
            suporte = novo,
            sondas = if (novo.sondaPre == null) DiagnosticoSondas(BloqueioSondas.SEM_SONDA) else _estado.value.sondas,
        )
    }

    fun fechar() = synchronized(trava) { aberta = false }

    fun novaSessao() = synchronized(trava) {
        suporte = SuporteInjecao()
        banco1.limpar()
        banco2.limpar()
        analise = AnaliseSondas(null, temPos = false, diesel = false)
        conclusao = null
        curva.clear()
        _estado.value = EstadoInjecao()
    }

    /** Um instantâneo da telemetria; as médias e as sondas só contam o que foi lido **neste** ciclo. */
    fun amostrar(dados: DadosObd) = synchronized(trava) {
        if (!aberta) return@synchronized
        val agora = relogioMs()
        fun fresco(chave: String?) = chave?.takeIf { dados.frescos?.contains(it) != false }?.let { dados[it] }
        val regime = AnaliseMistura.regime(dados[Pids.RPM], dados[Pids.VELOCIDADE], dados[Pids.CARGA_MOTOR])
        val agua = dados[Pids.TEMPERATURA_MOTOR]
        // Motor frio: a ECU ainda corrige em malha aberta; fora das médias.
        val regimeValido = regime.takeIf { agua == null || agua >= AGUA_MINIMA_TRIM }

        fun leitura(acumulador: AcumuladorTrim, s: SuporteBanco): LeituraBanco {
            val stft = s.stft?.let { dados[it] }
            val ltft = s.ltft?.let { dados[it] }
            val total = AnaliseMistura.total(stft, ltft, s.stft != null, s.ltft != null)
            if (total != null && (fresco(s.stft) != null || fresco(s.ltft) != null)) acumulador.registrar(total, regimeValido)
            return leituraDe(acumulador).copy(stft = stft, ltft = ltft, total = total)
        }

        val pre = fresco(suporte.sondaPre)
        val pos = fresco(suporte.sondaPos)
        val sondas = analise.registrar(agora, agua, dados[Pids.RPM], pre, pos)
        if (sondas.conclusivo) conclusao = sondas
        if (pre != null || pos != null) {
            curva.addLast(PontoSondas(agora, suporte.sondaPre?.let { dados[it] }, suporte.sondaPos?.let { dados[it] }))
            while (curva.isNotEmpty() && agora - curva.first().instanteMs > JANELA_CURVA_MS) curva.removeFirst()
        }
        _estado.value = EstadoInjecao(
            suporte = suporte,
            banco1 = leitura(banco1, suporte.banco1),
            banco2 = leitura(banco2, suporte.banco2),
            regime = regime,
            agua = agua,
            sondaPre = suporte.sondaPre?.let { dados[it] },
            sondaPos = suporte.sondaPos?.let { dados[it] },
            sondas = sondas,
            curva = curva.toList(),
        )
    }

    /** Null sem nenhuma leitura de trim nem análise de sondas concluída na sessão. */
    fun resumo(): ResumoInjecao? = synchronized(trava) {
        fun banco(a: AcumuladorTrim) = a.ultimoTotal?.let { leituraDe(a).copy(total = it) }
        val b1 = banco(banco1)
        val b2 = banco(banco2)
        if (b1 == null && b2 == null && conclusao == null) null else ResumoInjecao(b1, b2, conclusao)
    }

    private fun leituraDe(a: AcumuladorTrim) = LeituraBanco(
        lenta = a.media(RegimeMotor.LENTA),
        carga = a.media(RegimeMotor.CARGA),
        amostrasLenta = a.amostras(RegimeMotor.LENTA),
        amostrasCarga = a.amostras(RegimeMotor.CARGA),
    )

    companion object {
        /** Abaixo desta água os trims não entram nas médias. */
        const val AGUA_MINIMA_TRIM = 70.0
        const val JANELA_CURVA_MS = 60_000L
    }
}
