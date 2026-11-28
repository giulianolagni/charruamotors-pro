package com.charruamotors.ui.carros

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityPidsCustomizadosBinding
import com.charruamotors.databinding.DialogPidCustomizadoBinding
import com.charruamotors.databinding.ItemPerfilTecnicoBinding
import com.charruamotors.obd.pid.ImportadorPids
import com.charruamotors.obd.pid.PidCustomizado
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Importa, cadastra e remove os PIDs customizados de um carro (isolados no perfil dele). */
class PidsCustomizadosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPidsCustomizadosBinding
    private val app get() = application as CharruaMotorsApp
    private val veiculoId by lazy { intent.getLongExtra(EXTRA_VEICULO_ID, 0L) }
    private var descricao = ""

    private val abrirArquivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importar) }

    private val pids: List<PidCustomizado> get() = app.perfisCarro.ler(veiculoId).pidsCustomizados

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityPidsCustomizadosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (veiculoId <= 0) return finish()
        binding.botaoImportar.setOnClickListener { abrirArquivo.launch(arrayOf("*/*")) }
        binding.botaoAdicionar.setOnClickListener { editar(null) }
        binding.botaoCompartilhar.setOnClickListener { compartilhar() }
        binding.botaoRemoverTodos.setOnClickListener { confirmarRemocao() }
        lifecycleScope.launch {
            descricao = app.veiculoRepository.buscarPorId(veiculoId)?.descricao ?: return@launch finish()
            binding.toolbar.subtitle = descricao
        }
        mostrar()
    }

    private fun mostrar() {
        val lista = pids
        binding.textoVazio.isVisible = lista.isEmpty()
        binding.botaoCompartilhar.isVisible = lista.isNotEmpty()
        binding.botaoRemoverTodos.isVisible = lista.isNotEmpty()
        binding.listaPids.removeAllViews()
        for (pid in lista) {
            val linha = ItemPerfilTecnicoBinding.inflate(layoutInflater, binding.listaPids, true)
            linha.textoTitulo.text = pid.nome
            linha.textoDetalhe.text = listOfNotNull(pid.cabecalho, pid.modoPid, pid.equacao, pid.unidade.ifEmpty { null }).joinToString(" · ")
            linha.root.setOnClickListener { editar(pid) }
        }
    }

    private fun gravar(novos: List<PidCustomizado>) {
        app.perfisCarro.atualizar(veiculoId) { it.copy(pidsCustomizados = novos) }
        mostrar()
    }

    private fun importar(uri: Uri) {
        lifecycleScope.launch {
            val texto = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { leitor ->
                        // Lê no máximo o limite (+1, para o importador recusar o que passar dele).
                        val buffer = CharArray(ImportadorPids.MAXIMO_CARACTERES + 1)
                        var total = 0
                        while (total < buffer.size) {
                            val lidos = leitor.read(buffer, total, buffer.size - total)
                            if (lidos < 0) break
                            total += lidos
                        }
                        String(buffer, 0, total)
                    }
                }.getOrNull()
            }
            if (texto == null) {
                Snackbar.make(binding.root, R.string.pids_erro_arquivo, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val resultado = ImportadorPids.importar(texto)
            val atuais = pids
            val novos = PidCustomizado.mesclar(atuais, resultado.pids)
            val chaves = novos.map { it.chave }.toSet()
            val importados = resultado.pids.count { it.chave in chaves }
            val erros = resultado.erros.toMutableList()
            if (importados < resultado.pids.size) erros += getString(R.string.pids_erro_limite, PidCustomizado.MAXIMO_POR_VEICULO)
            gravar(novos)
            val mensagem = buildString {
                append(getString(R.string.pids_importados, importados))
                if (erros.isNotEmpty()) {
                    append("\n\n").append(getString(R.string.pids_recusados, erros.size)).append('\n')
                    append(erros.take(MAXIMO_ERROS).joinToString("\n"))
                }
            }
            MaterialAlertDialogBuilder(this@PidsCustomizadosActivity)
                .setTitle(R.string.pids_resultado_titulo)
                .setMessage(mensagem)
                .setPositiveButton(R.string.botao_fechar, null)
                .show()
        }
    }

    /** [atual] null = cadastro novo. O diálogo só fecha com um PID válido. */
    private fun editar(atual: PidCustomizado?) {
        val campos = DialogPidCustomizadoBinding.inflate(layoutInflater)
        atual?.let {
            campos.campoNome.setText(it.nome)
            campos.campoNomeCurto.setText(it.nomeCurto)
            campos.campoModo.setText(it.modoPid)
            campos.campoEquacao.setText(it.equacao)
            campos.campoMin.setText(if (it.min == PidCustomizado.MIN_PADRAO) "" else it.min.toString())
            campos.campoMax.setText(if (it.max == PidCustomizado.MAX_PADRAO) "" else it.max.toString())
            campos.campoUnidade.setText(it.unidade)
            campos.campoCabecalho.setText(it.cabecalho.orEmpty())
        }
        val construtor = MaterialAlertDialogBuilder(this)
            .setTitle(if (atual == null) R.string.pids_novo else R.string.pids_editar)
            .setView(campos.root)
            .setPositiveButton(R.string.pids_salvar, null)
            .setNegativeButton(R.string.botao_cancelar, null)
        if (atual != null) construtor.setNeutralButton(R.string.pids_remover) { _, _ -> gravar(pids - atual) }
        val dialogo = construtor.show()
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val novo = ler(campos)
                val lista = pids
                val posicao = lista.indexOf(atual)
                require(posicao >= 0 || lista.size < PidCustomizado.MAXIMO_POR_VEICULO) {
                    getString(R.string.pids_erro_limite, PidCustomizado.MAXIMO_POR_VEICULO)
                }
                val trocada = if (posicao >= 0) lista.toMutableList().also { l -> l[posicao] = novo } else lista + novo
                gravar(PidCustomizado.mesclar(emptyList(), trocada))
                dialogo.dismiss()
            } catch (e: IllegalArgumentException) {
                campos.textoErro.text = e.message
                campos.textoErro.isVisible = true
                campos.root.scrollTo(0, 0)
            }
        }
    }

    private fun ler(c: DialogPidCustomizadoBinding): PidCustomizado {
        fun texto(campo: android.widget.EditText) = campo.text?.toString()?.trim().orEmpty()
        fun numero(campo: android.widget.EditText): Double? = texto(campo).takeIf { it.isNotEmpty() }
            ?.let { requireNotNull(it.replace(',', '.').toDoubleOrNull()) { getString(R.string.pids_erro_numero) } }
        return PidCustomizado.criar(
            nome = texto(c.campoNome),
            nomeCurto = texto(c.campoNomeCurto),
            modoPid = texto(c.campoModo),
            equacao = texto(c.campoEquacao),
            min = numero(c.campoMin),
            max = numero(c.campoMax),
            unidade = texto(c.campoUnidade),
            cabecalho = texto(c.campoCabecalho),
        )
    }

    private fun compartilhar() {
        val envio = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TEXT, ImportadorPids.exportar(pids))
        startActivity(Intent.createChooser(envio, getString(R.string.pids_compartilhar)))
    }

    private fun confirmarRemocao() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pids_remover_todos)
            .setMessage(getString(R.string.pids_remover_todos_pergunta, pids.size, descricao))
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.pids_remover) { _, _ -> gravar(emptyList()) }
            .show()
    }

    companion object {
        private const val EXTRA_VEICULO_ID = "veiculo_id"
        private const val MAXIMO_ERROS = 10

        fun intent(context: Context, veiculoId: Long): Intent =
            Intent(context, PidsCustomizadosActivity::class.java).putExtra(EXTRA_VEICULO_ID, veiculoId)
    }
}
