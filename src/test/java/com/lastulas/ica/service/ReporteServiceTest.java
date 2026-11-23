package com.lastulas.ica.service;

import com.lastulas.ica.VentaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReporteServiceTest {

    private VentaRepository ventaRepo;
    private ReporteService servicio;

    @BeforeEach
    void setUp() {
        ventaRepo = mock(VentaRepository.class);
        servicio = new ReporteService(ventaRepo, mock(EntityManager.class), mock(UsuarioHistorialService.class));
    }

    @Test
    void graficoAgrupaLasVentasEmitidasPorDiaIncluyendoTodoElDiaActual() {
        LocalDate hoy = LocalDate.now();
        LocalDate ayer = hoy.minusDays(1);
        when(ventaRepo.totalesDiarios(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(
                        new Object[]{Date.valueOf(ayer), new BigDecimal("34.50"), 2L},
                        new Object[]{Date.valueOf(hoy), new BigDecimal("45.00"), 2L}));

        List<Map<String, Object>> serie = servicio.porDia(FiltroVentas.vacio());

        assertThat(serie).containsExactly(
                Map.of("fecha", ayer.toString(), "total", new BigDecimal("34.50"), "cantidad", 2),
                Map.of("fecha", hoy.toString(), "total", new BigDecimal("45.00"), "cantidad", 2));
        verify(ventaRepo).totalesDiarios(
                "EMITIDA", hoy.minusDays(6).atStartOfDay(), hoy.plusDays(1).atStartOfDay());
    }

    @Test
    void graficoRespetaUnRangoDeFechasYElDiaFinalEsInclusivo() {
        LocalDate desde = LocalDate.of(2026, 10, 8);
        LocalDate hasta = LocalDate.of(2026, 10, 9);
        when(ventaRepo.totalesDiarios(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(servicio.porDia(new FiltroVentas(null, desde, hasta,
                null, null, null, null, null))).isEmpty();

        verify(ventaRepo).totalesDiarios(
                "EMITIDA", desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay());
    }

    @Test
    void categoriasSumanLosImportesYLosPorcentajesUsanTodoElPeriodo() {
        Map<String, Object> reporte = ReporteService.resumirRendimiento(List.of(
                new Object[]{1, "Helado", 10, "HELADOS", 6L, new BigDecimal("60.00")},
                new Object[]{2, "Waffle", 20, "WAFLES", 3L, new BigDecimal("30.00")},
                new Object[]{3, "Otro helado", 10, "HELADOS", 1L, new BigDecimal("10.00")}));
        assertThat(reporte.get("totalVentas")).isEqualTo(new BigDecimal("100.00"));
        assertThat(reporte.get("totalUnidades")).isEqualTo(10L);
        assertThat(reporte.get("categorias")).isEqualTo(List.of(
                Map.of("nombre", "HELADOS", "monto", new BigDecimal("70.00"), "unidades", 7L, "porcentaje", new BigDecimal("70.0")),
                Map.of("nombre", "WAFLES", "monto", new BigDecimal("30.00"), "unidades", 3L, "porcentaje", new BigDecimal("30.0"))));
    }

    @Test
    void rankingLimitaADiezSinRecortarElDenominadorDePorcentajes() {
        java.util.ArrayList<Object[]> filas = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) filas.add(new Object[]{i, "Producto " + i, null, "Sin categoría", 1L, BigDecimal.ONE});
        Map<String, Object> reporte = ReporteService.resumirRendimiento(filas);
        assertThat(reporte.get("totalUnidades")).isEqualTo(12L);
        assertThat(reporte.get("totalProductos")).isEqualTo(12);
        List<?> ranking = (List<?>) reporte.get("productos");
        assertThat(ranking).hasSize(10);
        assertThat(((Map<?, ?>) ranking.getFirst()).get("porcentajeUnidades")).isEqualTo(new BigDecimal("8.3"));
    }

    @Test
    void dashboardVacioNoDividePorCero() {
        Map<String, Object> reporte = ReporteService.resumirRendimiento(List.of());
        assertThat(reporte.get("totalUnidades")).isEqualTo(0L);
        assertThat((List<?>) reporte.get("categorias")).isEmpty();
        assertThat((List<?>) reporte.get("productos")).isEmpty();
    }
}
