package com.charruamotors.ui.oficina.sensores

import com.charruamotors.CharruaMotorsApp
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.log.ColunasCelular
import com.charruamotors.obd.pid.DefinicaoPid
import com.charruamotors.obd.pid.Pids

/** Sensores que a conexão atual suporta (ECU, montadora e os calculados pelo app). */
fun CharruaMotorsApp.definicoesDaSessao(): List<DefinicaoPid> {
    val suportados = sessaoObd.pidsSuportados.value
    // As colunas opcionais do log (posição e Força G) não são sensores destas telas.
    val doApp = sessaoObd.colunasDoAparelho(suportados).filterNot { it.comando in ColunasCelular.COMANDOS }
    // O boost é só de tela (o `.csv` guarda o MAP bruto): entra aqui, não nas colunas do log.
    val boost = listOfNotNull(DefinicaoPid.PRESSAO_BOOST.takeIf { Pids.PRESSAO_COLETOR in suportados })
    return (sessaoObd.definicoesSuportadas(suportados) + doApp + boost).distinctBy { it.comando }
}

/**
 * Tudo o que o perfil do veículo da sessão prevê, respondendo ou não: o catálogo OBD2 (nos modos
 * 21/22, só os espelhos e os comandos AT) e os PIDs da montadora — nenhum no OBD2 Genérico.
 */
fun CharruaMotorsApp.definicoesDoPerfil(): List<DefinicaoPid> {
    val conexao = sessaoObd.infoConexao.value
    val padraoObd2 = conexao.modo == ModoComunicacaoEcu.OBD2_PADRAO
    val padrao = if (padraoObd2) catalogoPids.pids else conexao.espelhos + catalogoPids.pids.filter { it.ehComandoAt }
    val montadora = catalogoEstendido.perfilPara(sessaoObd.marcaMontadora)?.requisicoes
        ?.filterKeys { padraoObd2 || it.servico == conexao.modo.servico }
        ?.values?.flatten().orEmpty()
    return (definicoesDaSessao() + padrao + montadora).distinctBy { it.comando }
}
