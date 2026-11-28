package com.charruamotors.domain.perfis

import com.charruamotors.domain.model.TipoCombustivel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SugestorPerfilTest {

    private val catalogo = CatalogoPerfis.carregar(FontePerfis.doClasspath())

    private fun sugerir(
        vin: String,
        protocolo: Int?,
        combustivel: TipoCombustivel = TipoCombustivel.FLEX,
        modelo: String = "Carro",
        atual: String? = null,
        generico: Boolean = true,
    ) = SugestorPerfil.sugerir(
        catalogo, 1, atual, generico, DecodificadorVin.decodificar(vin, 2026), protocolo, modelo, combustivel,
    )?.preset?.id

    @Test
    fun `WMI e ATDPN escolhem o perfil exato ou o generico CAN`() {
        assertEquals("toyota_sw4_1kd", sugerir("8AJYZ59G0D3000001", 6, TipoCombustivel.DIESEL))
        // Modo 21 também na linha K.
        assertEquals("toyota_sw4_1kd", sugerir("8AJYZ59G0D3000001", 5, TipoCombustivel.DIESEL))
        // Toyota fora do chassi conhecido (e Hilux sem confirmação de 3.0): nunca vira 1KD.
        assertEquals(IdentificadorPerfil.ID_DIESEL, sugerir("8AJHA8CD5K2000001", 6, TipoCombustivel.DIESEL, "Hilux 2.8"))
        for (wmi in listOf("9BG", "9BW", "9BD", "9BH")) {
            assertEquals(wmi, IdentificadorPerfil.ID_FLEX_ASPIRADO, sugerir("${wmi}ZZZZZ0K0000001", 8))
        }
        assertEquals("chevrolet_cruze_14t", sugerir("9BGZZZZZ0K0000001", 6, modelo = "Cruze 1.4 Turbo"))
        // Linha K sem perfil da montadora, protocolo ilegível, WMI desconhecido: nada a sugerir.
        for (p in listOf(3, 4, 5, null)) assertNull(sugerir("9BGZZZZZ0K0000001", p, modelo = "Cruze 1.4 Turbo"))
        assertNull(sugerir("ZZZZZZZZ0K0000001", 6))
        // Já em uso ou fora de um genérico.
        assertNull(sugerir("9BHZZZZZ0K0000001", 6, atual = IdentificadorPerfil.ID_FLEX_ASPIRADO))
        assertNull(sugerir("8AJYZ59G0D3000001", 6, generico = false))
    }

    @Test
    fun `lista de perfis mantem os fallbacks na busca e separa os validados`() {
        fun montar(busca: String) = ListaPerfisTecnicos.montar(catalogo, "obd2_generico", "Auto", busca)
        val ids = montar("1kd").filterIsInstance<ItemPerfil.Opcao>().map { it.id }
        assertEquals(
            listOf("obd2_generico", "generico_flex_aspirado", "generico_flex_turbo", "generico_diesel_cr", "toyota_sw4_1kd"),
            ids,
        )
        val secoes = montar("").filterIsInstance<ItemPerfil.Secao>().map { it.titulo }
        assertEquals("Toyota", secoes[1])
        assertTrue(secoes.drop(2).all { "base" in it })
    }
}
