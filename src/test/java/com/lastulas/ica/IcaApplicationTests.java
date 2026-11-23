package com.lastulas.ica;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IcaApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void contextLoads() {
	}

	@Test
	void publicPagesAndAssetsAreAvailableWithoutAuthentication() throws Exception {
		String[] pages = {
				"/index.html",
				"/inventario.html",
				"/reporte_boletas.html",
				"/reporte_general.html",
				"/anulacion_boletas.html",
				"/cierre_caja.html",
				"/auditoria.html",
				"/creacion_usuario.html",
				"/cajero.html",
				"/cajero_pago.html"
		};

		for (String page : pages) {
			mockMvc.perform(get(page)).andExpect(status().isOk());
		}

		mockMvc.perform(get("/cierre_caja.html"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("sidebar-nav")));
		mockMvc.perform(get("/auditoria.html"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("sidebar-nav")));
		mockMvc.perform(get("/estilo.css")).andExpect(status().isOk());
		mockMvc.perform(get("/script.js")).andExpect(status().isOk());
	}

	@Test
	void receiptPreviewIsPrintable() throws Exception {
		mockMvc.perform(get("/cajero_pago.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"imprimirVistaPreviaComprobante()")));
		mockMvc.perform(get("/estilo.css"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("@media print")));
	}

	@Test
	void annulmentPageRendersSaleDatesFromEachRecord() throws Exception {
		mockMvc.perform(get("/script.js"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"formatearFecha(b.fechaVenta)")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"formatearHora(b.fechaVenta)")))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("esc(fechaFormateada)"))));
	}


    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Test
    void categoriaFrutaExponeElIndicadorQueUsaInventario() throws Exception {
        Categoria fruta = new Categoria();
        fruta.setIdCategoria(19);
        fruta.setNombre("FRUTAS");
        fruta.setEsFruta(true);
        var json = objectMapper.readTree(objectMapper.writeValueAsString(fruta));
        org.assertj.core.api.Assertions.assertThat(json.get("esFruta").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.get("esTopping").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(json.get("esSabor").asBoolean()).isFalse();
    }
}
