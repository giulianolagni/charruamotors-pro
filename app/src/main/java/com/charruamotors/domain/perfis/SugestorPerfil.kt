package com.charruamotors.domain.perfis

import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.obd.ObdParser

/** Perfil técnico sugerido para o carro conectado; só vale depois que o usuário confirmar. */
data class SugestaoPerfil(val veiculoId: Long, val preset: PresetVeiculo, val motivo: String)

/**
 * Sugestão pelo WMI do VIN (`0902`) combinado ao protocolo (`ATDPN`), para carro novo ou em perfil
 * genérico. Quem decide se pode sugerir (perfil não escolhido à mão) é o chamador. Puro (JVM).
 */
object SugestorPerfil {

    /**
     * Na ordem: o perfil exato pelo chassi (WMI + VDS); o da montadora do WMI cujo modelo está no
     * cadastro; em CAN de 11 bits, o genérico CAN da motorização. Fora disso (linha K, J1850, CAN de
     * 29 bits) não há o que sugerir. Perfis específicos exigem o protocolo da mesma família.
     *
     * @param atualId perfil em uso (null = OBD-II básico); a sugestão igual a ele é descartada.
     * @param atualGenerico só um perfil genérico recebe sugestão.
     */
    fun sugerir(
        catalogo: CatalogoPerfis,
        veiculoId: Long,
        atualId: String?,
        atualGenerico: Boolean,
        vin: VinDecodificado?,
        protocolo: Int?,
        modelo: String?,
        combustivel: TipoCombustivel?,
    ): SugestaoPerfil? {
        if (!atualGenerico || vin == null || protocolo == null) return null
        val montadora = vin.montadora ?: return null
        val origem = "VIN ${vin.wmi} ($montadora) · protocolo $protocolo"
        val daMontadora = catalogo.sugestoesPara(montadora, vin.anoModelo).filter { compativel(it, protocolo) }
        val porChassi = catalogo.detectarPorVin(vin)?.takeIf { compativel(it, protocolo) }
        // A detecção completa do catálogo (modelo + confirmação), para uma Hilux 2.8 não virar 1KD.
        val porModelo = daMontadora.singleOrNull { it.perfil.deteccao?.reconhece(montadora, modelo, vin.anoModelo) == true }
        val preset = porChassi
            ?: porModelo
            ?: catalogo.genericoPara(combustivel, modelo)?.takeIf { protocolo in ObdParser.PROTOCOLOS_CAN_11_BITS }
            ?: return null
        if (preset.id == atualId) return null
        return SugestaoPerfil(veiculoId, preset, origem)
    }

    /**
     * A família do protocolo do perfil (`ATSP6`–`9` = CAN; `3`–`5` = linha K; `0` = qualquer) bate com
     * a da ECU? Os perfis do Modo 21 (KWP) valem também na linha K.
     */
    fun compativel(preset: PresetVeiculo, protocolo: Int): Boolean {
        if (preset.servicoMontadora == "21" && protocolo in 3..5) return true
        val doPerfil = preset.perfilMotor.protocolo.uppercase().removePrefix("ATSP").toIntOrNull(16) ?: return true
        return when (doPerfil) {
            0 -> true
            in 6..9 -> protocolo in 6..9
            in 3..5 -> protocolo in 3..5
            else -> protocolo == doPerfil
        }
    }
}
