package com.charruamotors.oficina.sensores

import com.charruamotors.domain.calculo.AlavancaGmTcm
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids

/** Filtros da tela Todos os Sensores (v2.5.0). Um sensor pode estar em mais de uma categoria. */
enum class CategoriaSensor(val titulo: String) {
    MOTOR("Motor"),
    COMBUSTIVEL("Combustível/Injeção"),
    ADMISSAO("Admissão/Turbo"),
    TEMPERATURAS("Temperaturas"),
    TRANSMISSAO("Transmissão/TCM"),
    ELETRICA("Elétrica"),
    ;

    companion object {
        private val COMBUSTIVEL_OBD = setOf(
            "0106", "0107", "010A", "0114", Pids.PRESSAO_RAIL, "0124", "012F", "0134", "0144",
            Pids.PERCENTUAL_ETANOL, Pids.CONSUMO_ECU, Pids.RELACAO_AF_REAL,
        )
        private val ADMISSAO_OBD = setOf(Pids.PRESSAO_COLETOR, Pids.TEMPERATURA_AR_ADMISSAO, Pids.MAF, Pids.BORBOLETA, "0133", "0145")
        private val TERMOS_TRANSMISSAO = listOf("ATF", "DSG", "TCM", "ALAVANCA", "MARCHA", "4WD", "VSC")

        /**
         * Categorias de um sensor: os PIDs padrão pelo comando e os de montadora pelo identificador e
         * pela unidade. O que não se encaixa em nenhuma é do motor.
         */
        fun de(definicao: DefinicaoPid): Set<CategoriaSensor> {
            val c = definicao.comando
            val padrao = c.startsWith("01")
            val categorias = buildSet {
                if (definicao.unidade == "°C") add(TEMPERATURAS)
                if (c == Pids.TEMPERATURA_MOTOR || c == "015C" || c == "VW_TEMP_OLEO") add(MOTOR)
                if (c in COMBUSTIVEL_OBD || (!padrao && ("INJ" in c || "RAIL" in c))) add(COMBUSTIVEL)
                if (c in ADMISSAO_OBD || (!padrao && ("TURBO" in c || "BARO" in c))) add(ADMISSAO)
                if (!padrao && (c == AlavancaGmTcm.CHAVE_POSICAO || TERMOS_TRANSMISSAO.any { it in c })) add(TRANSMISSAO)
                if (definicao.unidade == "V" && c != Pids.SONDA_LAMBDA_TENSAO) add(ELETRICA)
            }
            return categorias.ifEmpty { setOf(MOTOR) }
        }
    }
}
