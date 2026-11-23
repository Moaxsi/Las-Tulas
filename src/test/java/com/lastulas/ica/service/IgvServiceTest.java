package com.lastulas.ica.service;

import com.lastulas.ica.Parametro;
import com.lastulas.ica.ParametroRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El dinero no se prueba a ojo. Estos casos son los que un contador
 * terminaria reclamando si el desglose no cuadra al centimo.
 */
class IgvServiceTest {

    private ParametroRepository parametroRepo;
    private IgvService igv;

    @BeforeEach
    void setUp() {
        parametroRepo = mock(ParametroRepository.class);
        when(parametroRepo.findById(anyString())).thenReturn(Optional.empty());
        igv = new IgvService(parametroRepo, "0.18");
    }

    @Test
    @DisplayName("El total NO cambia: la base y el IGV se reparten el mismo importe")
    void elTotalSeConservaAlDesglosar() {
        // S/ 18.00 de carta. La base imponible son S/ 15.25 y el IGV S/ 2.75.
        BigDecimal total = new BigDecimal("18.00");

        BigDecimal base = igv.aBaseImponible(total, igv.tasaVigente());
        BigDecimal impuesto = igv.calcularIgv(total, base);

        assertThat(base).isEqualByComparingTo("15.25");
        assertThat(impuesto).isEqualByComparingTo("2.75");
        // La property que manda: base + IGV tiene que volver a dar el total.
        assertThat(base.add(impuesto)).isEqualByComparingTo(total);
    }

    @Test
    @DisplayName("Base + IGV cuadra al centimo en cualquier importe de carta")
    void siempreCuadraAlCentimo() {
        BigDecimal tasa = igv.tasaVigente();
        for (String importe : new String[]{"0.50", "3.00", "7.70", "12.99", "15.00", "44.90", "113.75"}) {
            BigDecimal total = new BigDecimal(importe);
            BigDecimal base = igv.aBaseImponible(total, tasa);
            BigDecimal impuesto = igv.calcularIgv(total, base);

            assertThat(base.add(impuesto))
                    .as("total %s: base %s + igv %s", importe, base, impuesto)
                    .isEqualByComparingTo(total);
        }
    }

    @Test
    @DisplayName("El IGV es exactamente el 18% de la base imponible")
    void elIgvEsElDieciochoPorCientoDeLaBase() {
        BigDecimal total = new BigDecimal("35.40");
        BigDecimal base = igv.aBaseImponible(total, igv.tasaVigente());
        BigDecimal impuesto = igv.calcularIgv(total, base);

        BigDecimal esperado = base.multiply(new BigDecimal("0.18")).setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(impuesto).isEqualByComparingTo(esperado);
    }

    @Test
    @DisplayName("La tasa sale de la tabla parametros, no del codigo")
    void laTasaEsDinamica() {
        when(parametroRepo.findById("IGV_TASA"))
                .thenReturn(Optional.of(new Parametro("IGV_TASA", "0.05", "Tasa reducida")));

        assertThat(new IgvService(parametroRepo, "0.18").tasaVigente()).isEqualByComparingTo("0.05");
    }

    @Test
    @DisplayName("Una tasa basura en la base no tumba el cobro: se usa la de defecto")
    void tasaInvalidaCaeAlValorPorDefecto() {
        when(parametroRepo.findById("IGV_TASA"))
                .thenReturn(Optional.of(new Parametro("IGV_TASA", "no-es-un-numero", "roto")));

        assertThat(new IgvService(parametroRepo, "0.18").tasaVigente()).isEqualByComparingTo("0.18");
    }
}
