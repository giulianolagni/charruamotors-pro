package com.charruamotors.oficina.bicos

import com.charruamotors.carros.PerfilCarro
import com.charruamotors.domain.faixas.ResolucaoPerfil
import kotlin.math.abs

/** Situação da correção de um bico injetor (`7E0 · 21AD`), do mais saudável ao pior. */
enum class SituacaoBico(val texto: String) {
    OK("OK"),
    ATENCAO("Atenção"),
    FORA("Fora da faixa"),
    SEM_LEITURA("N/D"),
}

/**
 * Avaliação da correção dos 4 bicos injetores da 1KD-FTV (mm³/st): verde (`OK`) dentro de
 * ±[LIMITE_OK], amarelo até ±[LIMITE_ATENCAO] e vermelho acima. Função pura, testável na JVM.
 */
object AvaliacaoBicos {
    /** Faixa saudável da correção (mm³/st). */
    const val LIMITE_OK = 3.0

    /** Acima do saudável mas ainda dentro da escala do mostrador. */
    const val LIMITE_ATENCAO = 4.0

    /** Chaves dos bicos #1..#4 no catálogo estendido. */
    val CHAVES = listOf("TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4")

    /** Único motor com a leitura `21AD`. */
    const val MOTOR_1KD_FTV = "1KD-FTV"

    /**
     * v2.10.0: o bloco dos bicos só aparece no carro com perfil Toyota 1KD-FTV usando os recursos da
     * montadora (no OBD2 Genérico ou na camada "OBD2 padrão" o `21AD` não é lido).
     */
    fun disponivel(perfil: PerfilCarro, resolucao: ResolucaoPerfil): Boolean =
        perfil.usaMontadora && resolucao.preset?.motor == MOTOR_1KD_FTV

    fun avaliar(correcao: Double?): SituacaoBico = when {
        correcao == null || !correcao.isFinite() -> SituacaoBico.SEM_LEITURA
        abs(correcao) <= LIMITE_OK -> SituacaoBico.OK
        abs(correcao) <= LIMITE_ATENCAO -> SituacaoBico.ATENCAO
        else -> SituacaoBico.FORA
    }
}
