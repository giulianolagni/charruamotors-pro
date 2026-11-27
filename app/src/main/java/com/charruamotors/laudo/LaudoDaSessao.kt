package com.charruamotors.laudo

import com.charruamotors.domain.calculo.EstatisticasPercurso
import com.charruamotors.domain.calculo.FiltroVelocidadeEspuria
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.pid.Pids
import com.charruamotors.oficina.bateria.LaudoBateria
import com.charruamotors.oficina.bateria.ResumoTesteBateria
import com.charruamotors.oficina.sensores.MinMax

/** Partes do laudo da sessão em curso. Puro (JVM). */
object LaudoDaSessao {

    /** Só o scanner Bluetooth conectado produz um laudo de dados reais. */
    fun origem(modo: ModoObd, conectado: Boolean): OrigemLaudo =
        if (conectado && modo == ModoObd.BLUETOOTH) OrigemLaudo.CONEXAO_REAL else OrigemLaudo.SIMULADO

    /** v2.8.0: o teste de bateria concluído, para a seção própria do laudo. */
    fun testeBateria(r: ResumoTesteBateria) = TesteBateriaLaudo(
        repouso = LaudoBateria.linhaRepouso(r.repouso),
        partida = LaudoBateria.linhaPartida(r.partida),
        alternador = LaudoBateria.linhaAlternador(r),
        veredito = "Bateria ${LaudoBateria.vereditoBateria(r).titulo} · alternador ${LaudoBateria.vereditoAlternador(r).titulo}",
        condicoes = "motor ${if (r.diesel) "diesel" else "gasolina/flex"} · leitura pela porta OBD2 com ajuste de ${LaudoBateria.offset(r.offsetV)}",
    )

    /** [extremos] da sessão inteira por comando e as estatísticas da [viagem] em curso. */
    fun resumo(extremos: Map<String, MinMax>, viagem: EstatisticasPercurso): ResumoTecnicoLaudo {
        fun faixa(comando: String) = extremos[comando]?.let { FaixaLida(it.min, it.max) }
        // Zerar a viagem não apaga os máximos da sessão; o 255 espúrio cai para o máximo filtrado.
        val velocidade = extremos[Pids.VELOCIDADE]?.max?.takeIf { it < FiltroVelocidadeEspuria.VALOR_ESPURIO_KMH }
            ?: viagem.velocidadeMaximaKmh
        val comViagem = viagem.segundosTotais > 0
        return ResumoTecnicoLaudo(
            tensao = faixa(Pids.TENSAO_BATERIA),
            agua = faixa(Pids.TEMPERATURA_MOTOR),
            atf = Pids.COMANDOS_ATF.firstNotNullOfOrNull(::faixa),
            velocidadeMaximaKmh = velocidade,
            rpmMaximo = extremos[Pids.RPM]?.max ?: viagem.rpmMaximo,
            duracaoSegundos = viagem.segundosTotais.takeIf { comViagem },
            distanciaKm = viagem.distanciaKm.takeIf { comViagem },
            segundosMovimento = viagem.segundosMovimento.takeIf { comViagem },
            segundosParado = viagem.segundosParado.takeIf { comViagem },
            velocidadeMediaKmh = viagem.velocidadeMediaKmh,
            litros = viagem.litros.takeIf { it > 0 },
            kmPorLitro = viagem.kmPorLitro,
        )
    }
}
