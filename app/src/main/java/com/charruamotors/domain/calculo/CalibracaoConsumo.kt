package com.charruamotors.domain.calculo

import com.charruamotors.domain.model.TipoCombustivel

/** Combustível escolhido na calibração do consumo de um carro. */
enum class CombustivelCalibracao(val titulo: String, val tipo: TipoCombustivel) {
    GASOLINA("Gasolina", TipoCombustivel.GASOLINA),
    ETANOL("Etanol", TipoCombustivel.ETANOL),
    DIESEL_S10("Diesel S10", TipoCombustivel.DIESEL),
    ;

    companion object {
        fun deCodigo(codigo: String?): CombustivelCalibracao? = entries.firstOrNull { it.name.equals(codigo?.trim(), ignoreCase = true) }
    }
}

/**
 * Calibração do consumo de **um** carro (v2.6.0, no perfil isolado). Tudo opcional: o campo vazio
 * segue o cadastro do veículo, então a calibração padrão não muda nenhum cálculo. Puro (JVM).
 *
 * @property cilindradaLitros null = lida do modelo no cadastro.
 * @property combustivel null = o do cadastro (no flex, a fração de etanol vem do `0152`).
 * @property eficienciaVolumetrica fator de correção dos carros sem MAF (MAP + IAT + RPM); null = 0,85.
 *   No diesel, preenchê-la é o que autoriza estimar o consumo pelo MAP.
 * @property precoLitro R$ por litro, para o custo da viagem.
 * @property ajustePercentual ajuste fino pela bomba, aplicado sobre a calibração do cadastro.
 */
data class CalibracaoConsumo(
    val cilindradaLitros: Double? = null,
    val combustivel: CombustivelCalibracao? = null,
    val eficienciaVolumetrica: Double? = null,
    val precoLitro: Double? = null,
    val ajustePercentual: Double = 0.0,
) {
    /** Parâmetros dos cálculos de consumo: o cadastro do veículo com esta calibração por cima. */
    fun parametros(modelo: String?, tipoCadastro: TipoCombustivel?, calibracaoCadastro: Double = 1.0): ParametrosMotor {
        val base = ParametrosMotor.deVeiculo(modelo, combustivel?.tipo ?: tipoCadastro, calibracaoCadastro)
        return base.copy(
            cilindradaLitros = cilindradaLitros?.takeIf { it in FAIXA_CILINDRADA_L } ?: base.cilindradaLitros,
            eficienciaVolumetrica = eficienciaVolumetrica?.takeIf { it in FAIXA_EFICIENCIA } ?: base.eficienciaVolumetrica,
            calibracao = (base.calibracao * (1 + ajustePercentual / 100.0))
                .coerceIn(ParametrosMotor.CALIBRACAO_MIN, ParametrosMotor.CALIBRACAO_MAX),
            estimarDieselPorMap = base.tipoCombustivel == TipoCombustivel.DIESEL && eficienciaVolumetrica != null,
        )
    }

    /** O que muda a estimativa de litros (o preço não): mudou isto, o contador do tanque recomeça. */
    fun mesmaEstimativa(outra: CalibracaoConsumo): Boolean = copy(precoLitro = null) == outra.copy(precoLitro = null)

    /** Mensagem do primeiro campo fora da faixa; null = válida. */
    fun validar(): String? = when {
        cilindradaLitros != null && cilindradaLitros !in FAIXA_CILINDRADA_L -> "Cilindrada entre 0,6 e 8,0 L"
        eficienciaVolumetrica != null && eficienciaVolumetrica !in FAIXA_EFICIENCIA -> "Eficiência volumétrica entre 30 e 130 %"
        precoLitro != null && precoLitro !in FAIXA_PRECO -> "Preço do litro entre R$ 0,50 e R$ 50,00"
        ajustePercentual !in FAIXA_AJUSTE -> "Ajuste entre -50 % e +100 %"
        else -> null
    }

    /**
     * Novo [ajustePercentual] a partir dos litros que couberam na bomba contra os estimados pelo app
     * (com o ajuste atual) no mesmo trecho: `(1 + novo) = (1 + atual) · bomba / app`. Null se algum
     * volume for pequeno demais para comparar ou se o ajuste sair de [FAIXA_AJUSTE] (valores trocados
     * ou trecho rodado com o app desconectado).
     */
    fun ajustePelaBomba(litrosBomba: Double, litrosApp: Double): Double? {
        if (!litrosBomba.isFinite() || !litrosApp.isFinite()) return null
        if (litrosBomba < LITROS_MINIMOS_AJUSTE || litrosApp < LITROS_MINIMOS_AJUSTE) return null
        val novo = ((1 + ajustePercentual / 100.0) * litrosBomba / litrosApp - 1) * 100.0
        return (kotlin.math.round(novo * 10.0) / 10.0).takeIf { it in FAIXA_AJUSTE }
    }

    /** `cc=1.4;comb=ETANOL;ve=0.9;preco=5.89;ajuste=3.5`; null = tudo padrão (chave removida). */
    fun codificar(): String? = listOfNotNull(
        cilindradaLitros?.let { "cc=$it" },
        combustivel?.let { "comb=${it.name}" },
        eficienciaVolumetrica?.let { "ve=$it" },
        precoLitro?.let { "preco=$it" },
        ajustePercentual.takeIf { it != 0.0 }?.let { "ajuste=$it" },
    ).joinToString(";").ifEmpty { null }

    companion object {
        val FAIXA_CILINDRADA_L = 0.6..8.0
        val FAIXA_EFICIENCIA = 0.3..1.3
        val FAIXA_PRECO = 0.5..50.0
        val FAIXA_AJUSTE = -50.0..100.0

        /** Abaixo disto a leitura da bomba e o erro da estimativa pesam demais. */
        const val LITROS_MINIMOS_AJUSTE = 5.0

        /** Campos ausentes, ilegíveis ou fora da faixa voltam ao padrão (nunca lança). */
        fun decodificar(texto: String?): CalibracaoConsumo {
            val campos = texto.orEmpty().split(';').mapNotNull {
                val partes = it.split('=', limit = 2)
                if (partes.size == 2) partes[0].trim() to partes[1].trim() else null
            }.toMap()
            fun numero(chave: String, faixa: ClosedFloatingPointRange<Double>) = campos[chave]?.toDoubleOrNull()?.takeIf { it in faixa }
            return CalibracaoConsumo(
                cilindradaLitros = numero("cc", FAIXA_CILINDRADA_L),
                combustivel = CombustivelCalibracao.deCodigo(campos["comb"]),
                eficienciaVolumetrica = numero("ve", FAIXA_EFICIENCIA),
                precoLitro = numero("preco", FAIXA_PRECO),
                ajustePercentual = numero("ajuste", FAIXA_AJUSTE) ?: 0.0,
            )
        }
    }
}
