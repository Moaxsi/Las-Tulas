package com.lastulas.ica;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdicionalesCobradosConverterTest {
    private final AdicionalesCobradosConverter converter = new AdicionalesCobradosConverter();

    @Test
    void conservaNombreYPrecioDelMomentoDeLaVenta() {
        var adicionales = List.of(new AdicionalCobrado(9, "Crema \"especial\", chocolate", new BigDecimal("2.50")));
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(adicionales)))
                .isEqualTo(adicionales);
    }

    @Test
    void ventasAnterioresNoInventanAdicionales() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }

    @Test
    void noOcultaUnDesgloseGuardadoIlegible() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("no es JSON"))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
