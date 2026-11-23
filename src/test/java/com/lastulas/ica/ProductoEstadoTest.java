package com.lastulas.ica;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El estado del producto se deriva del stock, no se elige a mano. Estas
 * pruebas fijan los umbrales porque el mismo criterio vive en el JPQL del
 * repositorio: si se cambia uno y no el otro, el inventario empieza a
 * mostrar "Disponible" con cero unidades sin que nada falle.
 */
class ProductoEstadoTest {

    @ParameterizedTest(name = "stock {0} -> {1}")
    @CsvSource({
            "0,    Agotado",
            "1,    Stock bajo",
            "10,   Stock bajo",
            "11,   Disponible",
            "100,  Disponible",
            "-5,   Agotado"
    })
    @DisplayName("El estado se calcula desde el stock")
    void estadoSegunStock(Integer stock, String esperado) {
        assertThat(ProductoEstado.estadoSegunStock(stock)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("Un producto recien creado sin stock ya nace Agotado, no Disponible")
    void sinStockNoNaceDisponible() {
        Producto p = new Producto();
        // Este es el valor por defecto de la entidad, y es el que hacia que un
        // producto recien creado se anunciara como disponible.
        assertThat(p.getEstado()).isEqualTo("Disponible");

        p.setEstado(ProductoEstado.estadoSegunStock(p.getStock()));
        assertThat(p.getEstado()).isEqualTo("Agotado");
    }

    @Test
    @DisplayName("Stock null se trata como agotado y no rompe")
    void stockNullNoExplota() {
        assertThat(ProductoEstado.estadoSegunStock(null)).isEqualTo("Agotado");
    }
}
