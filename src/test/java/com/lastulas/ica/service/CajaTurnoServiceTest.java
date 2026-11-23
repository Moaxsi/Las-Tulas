package com.lastulas.ica.service;

import com.lastulas.ica.CajaTurno;
import com.lastulas.ica.Auditoria;
import com.lastulas.ica.AuditoriaRepository;
import java.util.ArrayList;
import java.util.List;
import com.lastulas.ica.CajaTurnoRepository;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import com.lastulas.ica.security.UsuarioPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Punto 11: arqueo de caja.
 *
 * Lo que estas pruebas protegen es que el descuadre sea un CALCULO y no un
 * numero que escribe el cajero. Si el esperado saliera de un campo editable, un
 * arqueo mal hecho daria siempre 0 y el control no serviria de nada.
 */
class CajaTurnoServiceTest {

    private CajaTurnoRepository cajaRepo;
    private UsuarioRepository usuarioRepo;
    private AuditoriaService auditoria;
    private CajaTurnoService servicio;
    private UsuarioPrincipal administrador;
    private AuditoriaRepository auditoriaRepo;
    private List<Auditoria> movimientos;

    @BeforeEach
    void setUp() {
        cajaRepo = mock(CajaTurnoRepository.class);
        usuarioRepo = mock(UsuarioRepository.class);
        auditoria = mock(AuditoriaService.class);

        when(cajaRepo.save(any(CajaTurno.class))).thenAnswer(inv -> {
            CajaTurno t = inv.getArgument(0);
            t.setIdCajaTurno(9);
            return t;
        });
        when(usuarioRepo.findById(anyInt())).thenReturn(Optional.of(usuario()));

        auditoriaRepo = mock(AuditoriaRepository.class);
        movimientos = new ArrayList<>();
        when(auditoriaRepo.findByTablaAndIdRegistroOrderByFechaDesc(any(), any())).thenAnswer(inv -> new ArrayList<>(movimientos));
        when(auditoriaRepo.saveAndFlush(any())).thenAnswer(inv -> { Auditoria a = inv.getArgument(0); movimientos.add(a); return a; });
        servicio = new CajaTurnoService(cajaRepo, usuarioRepo, auditoria, auditoriaRepo, mock(CajaOperacionLock.class));
        administrador = new UsuarioPrincipal(5, "Administrador", "admin", "ADMIN");

        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // APERTURA
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Abrir guarda el monto inicial y queda ABIERTA")
    void abrirTurnoGuardaElMontoInicial() {
        Map<String, Object> r = servicio.abrirTurno(new BigDecimal("50.00"), administrador);

        ArgumentCaptor<CajaTurno> guardada = ArgumentCaptor.forClass(CajaTurno.class);
        verify(cajaRepo).save(guardada.capture());
        assertThat(guardada.getValue().getEstado()).isEqualTo(CajaTurno.ABIERTA);
        assertThat(guardada.getValue().getMontoInicial()).isEqualByComparingTo("50.00");
        // El id del cajero sale del token, no del body de la peticion.
        assertThat(guardada.getValue().getIdUsuario()).isEqualTo(5);
        assertThat(r.get("abierto")).isEqualTo(true);
    }

    @Test
    @DisplayName("No se pueden abrir dos turnos a la vez")
    void dosTurnosAbiertosSeRechazan() {
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA))
                .thenReturn(List.of(turnoAbierto()));

        assertThatThrownBy(() -> servicio.abrirTurno(new BigDecimal("10.00"), administrador))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya tiene un turno abierto");

        verify(cajaRepo, never()).save(any(CajaTurno.class));
    }

    @Test
    @DisplayName("Un monto inicial negativo se rechaza")
    void montoInicialNegativoSeRechaza() {
        assertThatThrownBy(() -> servicio.abrirTurno(new BigDecimal("-5.00"), administrador))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("negativo");
    }

    // ------------------------------------------------------------------
    // ARQUEO
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El efectivo esperado es la base mas lo cobrado en efectivo del turno")
    void elEsperadoEsBaseMasEfectivo() {
        CajaTurno turno = turnoAbierto();
        // Base 50.00, el turno venta 120.00 de los cuales 80.00 fueron en efectivo.
        when(cajaRepo.totalesDelTurno(anyInt(), any())).thenReturn(
                totales("120.00", 4L, "80.00", "40.00"));

        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA))
                .thenReturn(List.of(turno));

        // El cajero conto 130.00: sobran 0 porque esperaba exactamente eso.
        Map<String, Object> r = servicio.cerrarTurno(new BigDecimal("130.00"), null, administrador);

        // El mapa viene sin tipo generico (sale de JSON), asi que los montos se
        // castean a BigDecimal antes de comparar: sin cast, el assert solo
        // comprueba que haya un objeto cualquiera.
        assertThat((BigDecimal) r.get("efectivoEsperado")).isEqualByComparingTo("130.00");
        assertThat((BigDecimal) r.get("diferencia")).isEqualByComparingTo("0.00");
        assertThat((Integer) r.get("totalBoletas")).isEqualTo(4);
        assertThat((BigDecimal) r.get("totalDigital")).isEqualByComparingTo("40.00");
        assertThat(turno.getEstado()).isEqualTo(CajaTurno.CERRADA);
    }

    @Test
    @DisplayName("Si falta efectivo la diferencia queda en negativo")
    void faltaDeEfectivoQuedaNegativa() {
        when(cajaRepo.totalesDelTurno(anyInt(), any())).thenReturn(
                totales("120.00", 4L, "80.00", "40.00"));
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA))
                .thenReturn(List.of(turnoAbierto()));

        Map<String, Object> r = servicio.cerrarTurno(new BigDecimal("100.00"), "Faltaba un billete", administrador);

        assertThat((BigDecimal) r.get("diferencia")).isEqualByComparingTo("-30.00");
        assertThat(r.get("observaciones")).isEqualTo("Faltaba un billete");
    }

    @Test
    @DisplayName("Sin efectivo contado no hay arqueo: el cierre se rechaza")
    void sinEfectivoContadoNoSeCierra() {
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA))
                .thenReturn(List.of(turnoAbierto()));

        assertThatThrownBy(() -> servicio.cerrarTurno(null, null, administrador))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("efectivo contaste");

        verify(cajaRepo, never()).save(any(CajaTurno.class));
    }

    @Test
    @DisplayName("Sin turno abierto no se puede cerrar caja")
    void sinTurnoNoSeCierra() {
        assertThatThrownBy(() -> servicio.cerrarTurno(new BigDecimal("100.00"), null, administrador))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("turno de caja abierto");
    }

    @Test
    @DisplayName("Un turno cerrado ya no se recalcula: el arqueo queda congelado")
    void turnoCerradoMantieneSusTotales() {
        // Turno cerrado hace un mes: los totales guardados NO coinciden con lo
        // que se vendio despues (una venta anulada de ayer). El historial tiene
        // que mostrar los del cierre, no los recalculados.
        CajaTurno cerrado = turnoAbierto();
        cerrado.setEstado(CajaTurno.CERRADA);
        cerrado.setTotalVentas(new BigDecimal("120.00"));
        cerrado.setTotalBoletas(4);
        cerrado.setTotalEfectivo(new BigDecimal("80.00"));
        cerrado.setTotalDigital(new BigDecimal("40.00"));

        when(cajaRepo.findAllByOrderByFechaAperturaDesc(any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(cerrado)));

        Map<String, Object> r = servicio.historial(null, org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent().get(0);

        assertThat((BigDecimal) r.get("totalVentas")).isEqualByComparingTo("120.00");
        assertThat((Integer) r.get("totalBoletas")).isEqualTo(4);
        // Si se recalculara, habria llamado al repositorio; no debe llamarse.
        verify(cajaRepo, never()).totalesDelTurno(anyInt(), any());
    }

    // ------------------------------------------------------------------

    @Test
    void cajeroSoloPuedeConsultarYNoGestionarTurnos() {
        UsuarioPrincipal cajero = new UsuarioPrincipal(5, "Cajero", "cajero", "CAJERO");
        assertThatThrownBy(() -> servicio.abrirTurno(BigDecimal.ZERO, cajero))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(403));
        assertThatThrownBy(() -> servicio.cerrarTurno(BigDecimal.ZERO, null, cajero))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(403));
        servicio.turnoActual(99, cajero);
        verify(cajaRepo).findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA);
        verify(cajaRepo, never()).save(any(CajaTurno.class));
    }

    @Test
    void administradorAbreUnaCajaGeneralIgnorandoSelectorAntiguo() {
        servicio.abrirTurno(new BigDecimal("20.00"), 8, administrador);
        verify(cajaRepo).save(org.mockito.ArgumentMatchers.argThat(t -> t.getIdUsuario().equals(5)));
        verify(auditoria).registrar(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(administrador));
    }

    @Test
    void aporteCubreFaltanteConNotaSinInflarVentas() {
        when(cajaRepo.totalesDelTurno(anyInt(), any())).thenReturn(
                totales("120.00", 4L, "80.00", "40.00"));
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of(turnoAbierto()));
        Map<String, Object> r = servicio.cerrarTurno(new BigDecimal("100.00"), "Revisión de cierre",
                new BigDecimal("30.00"), "Reposición de faltante", 5, administrador);
        assertThat((BigDecimal) r.get("efectivoContado")).isEqualByComparingTo("130.00");
        assertThat((BigDecimal) r.get("diferencia")).isZero();
        assertThat((BigDecimal) r.get("totalVentas")).isEqualByComparingTo("120.00");
        assertThat(r.get("observaciones").toString()).contains("Aporte S/ 30.00", "contado antes S/ 100.00", "-30.00", "Reposición de faltante");
        verify(cajaRepo).findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA);
    }

    @Test
    void aporteSinMotivoONegativoSeRechazaSinCerrar() {
        assertThatThrownBy(() -> servicio.cerrarTurno(BigDecimal.ZERO, null, BigDecimal.ONE, " ", 5, administrador))
                .hasMessageContaining("motivo");
        assertThatThrownBy(() -> servicio.cerrarTurno(BigDecimal.ZERO, null, new BigDecimal("-0.001"), "Error", 5, administrador))
                .hasMessageContaining("negativo");
        verify(cajaRepo, never()).save(any(CajaTurno.class));
    }

    @Test
    void rechazaAporteMayorAlFaltante() {
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of(turnoAbierto()));
        assertThatThrownBy(() -> servicio.cerrarTurno(new BigDecimal("40.00"), null, new BigDecimal("11.00"), "Reposición", 5, administrador))
                .hasMessageContaining("superar");
        verify(cajaRepo, never()).save(any(CajaTurno.class));
    }

    @Test
    void cajerosDistintosObtienenLaMismaCajaDelAdministrador() {
        CajaTurno turno = turnoAbierto();
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of(turno));
        assertThat(servicio.turnoAbierto(11).orElseThrow().getIdCajaTurno()).isEqualTo(9);
        assertThat(servicio.turnoAbierto(22).orElseThrow().getIdCajaTurno()).isEqualTo(9);
        Map<String, Object> vista = servicio.turnoActual(new UsuarioPrincipal(11, "Cajero", "c", "CAJERO"));
        assertThat(vista).containsEntry("abierto", true).doesNotContainKeys("montoInicial", "totalVentas", "movimientos");
    }

    @Test
    void ingresoRetiroYReposicionPersistenSinInflarVentasYReintentoNoDuplica() {
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of(turnoAbierto()));
        when(cajaRepo.totalesDelTurno(anyInt(), any())).thenReturn(totales("120.00", 4, "80.00", "40.00"));
        servicio.registrarMovimiento("INGRESO_CAJA", new BigDecimal("20.00"), "Fondos para vuelto", 9, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", administrador);
        servicio.registrarMovimiento("RETIRO_CAJA", new BigDecimal("10.00"), "Pago a delivery", 9, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", administrador);
        servicio.registrarMovimiento("REPOSICION_CAJA", new BigDecimal("5.00"), "Dinero faltante repuesto", 9, "cccccccc-cccc-cccc-cccc-cccccccccccc", administrador);
        Map<String, Object> r = servicio.registrarMovimiento("REPOSICION_CAJA", new BigDecimal("5.00"), "Dinero faltante repuesto", 9, "cccccccc-cccc-cccc-cccc-cccccccccccc", administrador);
        assertThat(movimientos).hasSize(3);
        assertThat((BigDecimal) r.get("efectivoEsperado")).isEqualByComparingTo("140.00");
        assertThat((BigDecimal) r.get("totalVentas")).isEqualByComparingTo("120.00");
        assertThat((List<?>) r.get("movimientos")).hasSize(3);
        Map<String, Object> cierre = servicio.cerrarCajaGeneral(new BigDecimal("140.00"), "Arqueo verificado", 9, administrador);
        assertThat((BigDecimal) cierre.get("diferencia")).isZero();
    }

    @Test
    void validaMotivoMontoTurnoYPermisosDeMovimientos() {
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of(turnoAbierto()));
        String clave = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        assertThatThrownBy(() -> servicio.registrarMovimiento("INGRESO_CAJA", BigDecimal.ONE, " ", 9, clave, administrador)).hasMessageContaining("motivo");
        assertThatThrownBy(() -> servicio.registrarMovimiento("INGRESO_CAJA", new BigDecimal("1.001"), "Motivo", 9, clave, administrador)).hasMessageContaining("decimales");
        assertThatThrownBy(() -> servicio.registrarMovimiento("RETIRO_CAJA", new BigDecimal("51"), "Delivery", 9, clave, administrador)).hasMessageContaining("supera");
        assertThatThrownBy(() -> servicio.registrarMovimiento("INGRESO_CAJA", BigDecimal.ONE, "Motivo", 8, clave, administrador)).hasMessageContaining("cambió");
        assertThatThrownBy(() -> servicio.registrarMovimiento("INGRESO_CAJA", BigDecimal.ONE, "Motivo", 9, clave, new UsuarioPrincipal(8, "C", "c", "CAJERO"))).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getHttpStatus()).isEqualTo(403));
        verify(auditoriaRepo, never()).saveAndFlush(any());
    }

    @Test
    void cierreConPantallaDeOtroTurnoNoCierraLaCajaNueva() {
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of(turnoAbierto()));
        assertThatThrownBy(() -> servicio.cerrarCajaGeneral(BigDecimal.TEN, "Nota", 8, administrador)).hasMessageContaining("cambió");
        verify(cajaRepo, never()).save(any());
    }

    @Test
    void variosTurnosAnterioresImpidenNuevosCobrosSinInventarArqueos() {
        CajaTurno segundo = turnoAbierto(); segundo.setIdCajaTurno(10);
        when(cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA)).thenReturn(List.of(turnoAbierto(), segundo));
        assertThatThrownBy(() -> servicio.turnoAbierto(11)).hasMessageContaining("anteriores");
        assertThat(servicio.turnoActual(administrador)).containsEntry("requiereRegularizacion", true);
        verify(cajaRepo, never()).save(any());
    }

    private CajaTurnoRepository.TotalesTurno totales(String ventas, long boletas, String efectivo, String digital) {
        return new CajaTurnoRepository.TotalesTurno() {
            public BigDecimal getTotalVentas() { return new BigDecimal(ventas); }
            public Long getTotalBoletas() { return boletas; }
            public BigDecimal getTotalEfectivo() { return new BigDecimal(efectivo); }
            public BigDecimal getTotalDigital() { return new BigDecimal(digital); }
        };
    }

    private CajaTurno turnoAbierto() {
        CajaTurno t = new CajaTurno();
        t.setIdCajaTurno(9);
        t.setIdUsuario(5);
        t.setFechaApertura(LocalDateTime.now().minusHours(6));
        t.setMontoInicial(new BigDecimal("50.00"));
        t.setEstado(CajaTurno.ABIERTA);
        return t;
    }

    private Usuario usuario() {
        Usuario u = new Usuario();
        u.setIdUsuario(5);
        u.setNombreCompleto("Luis Caja");
        return u;
    }
}
