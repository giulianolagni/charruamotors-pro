package com.charruamotors.obd.log

/**
 * Uma sessão gravada: o CSV de telemetria e/ou o relatório `.txt` de mesmo nome-base. Como
 * [ItemLog] usa o nome-base, então a ordenação por data e a pasta de simulados de
 * [OrganizadorLogs] valem para a sessão inteira.
 */
data class SessaoGravada<T : ItemLog>(
    val chave: String,
    val titulo: String,
    val csv: T?,
    val txt: T?,
) : ItemLog {
    override val nome: String get() = chave
    override val modificadoMs: Long get() = arquivos.maxOfOrNull { it.modificadoMs } ?: 0L

    val arquivos: List<T> get() = listOfNotNull(csv, txt)
}

/**
 * Junta `.txt` e `.csv` da mesma sessão (mesmo nome-base: data, hora e veículo) num item só. Arquivos
 * sem par (Data Logger manual, CSV desligado, arquivo apagado) viram sessões com um lado só. Puro (JVM).
 */
object AgrupadorSessoes {

    const val TITULO_MANUAL = "Gravação manual (Data Logger)"
    const val TITULO_SEM_VEICULO = "Sessão sem veículo"

    private const val SEM_VEICULO = "SemVeiculo"
    private val regexSessao = Regex("^CharruaOBD_(?:SIMULADO_)?\\d{8}_\\d{6}_(.+)$", RegexOption.IGNORE_CASE)
    private val regexManual = Regex("^charrua_obd_(?:SIMULADO_)?\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}.*$", RegexOption.IGNORE_CASE)

    /** Sessões sem ordem garantida: quem lista ordena por [OrganizadorLogs]. */
    fun <T : ItemLog> agrupar(arquivos: List<T>): List<SessaoGravada<T>> =
        arquivos.filter { ehCsv(it) || ehTxt(it) }
            .groupBy { base(it.nome).lowercase() }
            .values
            .flatMap { grupo ->
                val csvs = grupo.filter(::ehCsv)
                val txts = grupo.filter(::ehTxt)
                // Nomes repetidos (MediaStore e pasta do app): cada cópia continua visível.
                (0 until maxOf(csvs.size, txts.size)).map { i ->
                    val csv = csvs.getOrNull(i)
                    val txt = txts.getOrNull(i)
                    val chave = base((csv ?: txt)!!.nome)
                    SessaoGravada(chave, titulo(chave), csv, txt)
                }
            }

    /** Nome do veículo gravado no nome do arquivo; para outros nomes, o que descreve a origem. */
    fun titulo(chave: String): String {
        val veiculo = regexSessao.matchEntire(chave)?.groupValues?.get(1)
        return when {
            veiculo == SEM_VEICULO -> TITULO_SEM_VEICULO
            veiculo != null -> veiculo.replace('_', ' ').trim()
            regexManual.matches(chave) -> TITULO_MANUAL
            else -> chave
        }
    }

    private fun ehCsv(item: ItemLog) = item.nome.endsWith(".csv", ignoreCase = true)
    private fun ehTxt(item: ItemLog) = item.nome.endsWith(".txt", ignoreCase = true)
    private fun base(nome: String) = nome.substringBeforeLast('.', nome)
}
