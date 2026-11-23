package com.lastulas.ica.controller;

import com.lastulas.ica.security.UsuarioPrincipal;
import com.lastulas.ica.service.CajaTurnoService;
import com.lastulas.ica.service.ReporteService;
import com.lastulas.ica.service.FiltroVentas;
import com.lastulas.ica.service.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Punto 11: apertura, cierre y arqueo de caja.
 *
 * El administrador gestiona los turnos; cada cajero solo consulta su propia
 * actividad. El cierre conserva el conteo y cualquier aporte con su motivo.
 */
@RestController
@RequestMapping("/api/caja")
@CrossOrigin(originPatterns = "*")
public class CajaController {

    private static final int TAMANO_MAXIMO = 100;

    private final CajaTurnoService cajaTurnoService;
    private final ReporteService reporteService;

    public CajaController(CajaTurnoService cajaTurnoService, ReporteService reporteService) {
        this.cajaTurnoService = cajaTurnoService;
        this.reporteService = reporteService;
    }

    /** Estado del turno del cajero autenticado. Para pintar la pantalla de cobro. */
    @GetMapping("/actual")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public ResponseEntity<?> actual(@RequestParam(required = false) Integer idUsuario,
                                    @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(cajaTurnoService.turnoActual(titular(idUsuario, principal), principal));
    }

    @PostMapping("/apertura")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> abrir(@RequestBody(required = false) Map<String, Object> body,
                                   @AuthenticationPrincipal UsuarioPrincipal principal) {
        BigDecimal montoInicial = aDecimal(body != null ? body.get("montoInicial") : null);
        return ResponseEntity.ok(cajaTurnoService.abrirTurno(montoInicial, principal));
    }

    @PostMapping("/cierre")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> cerrar(@RequestBody(required = false) Map<String, Object> body,
                                    @AuthenticationPrincipal UsuarioPrincipal principal) {
        if (body == null || body.get("efectivoContado") == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Indica cuanto efectivo contaste en la gaveta."));
        }
        BigDecimal contado = aDecimal(body.get("efectivoContado"));
        String observaciones = body.get("observaciones") != null
                ? String.valueOf(body.get("observaciones")) : null;
        Integer turno;
        try { turno = Integer.valueOf(String.valueOf(body.get("idCajaTurno"))); }
        catch (NumberFormatException e) { throw new BusinessException("Actualiza el estado de la caja antes de cerrar."); }
        return ResponseEntity.ok(cajaTurnoService.cerrarCajaGeneral(contado, observaciones, turno, principal));
    }

    /** La consulta del cajero siempre se limita al propietario del token. */
    @GetMapping("/ventas")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public ResponseEntity<?> ventas(@RequestParam(defaultValue = "0") int pagina,
                                    @RequestParam(defaultValue = "10") int tamano,
                                    @RequestParam(required = false) Integer idUsuario,
                                    @AuthenticationPrincipal UsuarioPrincipal principal) {
        FiltroVentas filtro = new FiltroVentas(null, null, null, titular(idUsuario, principal), null, null, null, null);
        return ResponseEntity.ok(reporteService.listar(filtro, PageRequest.of(Math.max(0, pagina),
                Math.min(Math.max(1, tamano), TAMANO_MAXIMO), Sort.by(Sort.Direction.DESC, "idVenta"))));
    }

    private static Integer titular(Integer solicitado, UsuarioPrincipal principal) {
        return "ADMIN".equalsIgnoreCase(principal.getRol()) ? null : principal.getIdUsuario();
    }

    @GetMapping("/resumen-dia")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> resumenDia() {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));
        return ResponseEntity.ok(Map.of("fecha", hoy.toString(), "resumen", reporteService.resumen(
                new FiltroVentas(null, hoy, hoy, null, null, null, null, null)),
                "catalogo", reporteService.rendimientoProductos(hoy, hoy)));
    }

    @PostMapping("/movimientos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> movimiento(@RequestBody Map<String, Object> body,
                                        @AuthenticationPrincipal UsuarioPrincipal principal) {
        Integer turno;
        try { turno = Integer.valueOf(String.valueOf(body.get("idCajaTurno"))); }
        catch (NumberFormatException e) { throw new BusinessException("Actualiza el estado de la caja."); }
        return ResponseEntity.ok(cajaTurnoService.registrarMovimiento(
                String.valueOf(body.get("tipo")), aDecimal(body.get("monto")),
                body.get("motivo") == null ? null : body.get("motivo").toString(), turno,
                body.get("claveOperacion") == null ? null : body.get("claveOperacion").toString(), principal));
    }

    @PostMapping("/turnos-anteriores/{id}/cierre")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> cerrarAnterior(@PathVariable Integer id, @RequestBody Map<String, Object> body,
                                            @AuthenticationPrincipal UsuarioPrincipal principal) {
        return ResponseEntity.ok(cajaTurnoService.cerrarAnterior(id,
                body.get("efectivoContado") == null ? null : aDecimal(body.get("efectivoContado")),
                body.get("observaciones") == null ? null : body.get("observaciones").toString(), principal));
    }

    /** El arqueo de la caja general es exclusivo del administrador. */
    @GetMapping("/turnos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> turnos(@RequestParam(defaultValue = "0") int pagina,
                                    @RequestParam(defaultValue = "10") int tamano) {

        org.springframework.data.domain.Page<Map<String, Object>> turnos =
                cajaTurnoService.historial(
                        null,
                        PageRequest.of(Math.max(0, pagina),
                                Math.min(Math.max(1, tamano), TAMANO_MAXIMO),
                                Sort.by(Sort.Direction.DESC, "fechaApertura")));

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("contenido", turnos.getContent());
        respuesta.put("pagina", turnos.getNumber());
        respuesta.put("tamano", turnos.getSize());
        respuesta.put("totalPaginas", turnos.getTotalPages());
        respuesta.put("totalElementos", turnos.getTotalElements());
        // Los botones "anterior/siguiente" se desactivan con estos dos; sin
        // ellos el navegador no sabe si la ultima pagina ya es la final.
        respuesta.put("primera", turnos.isFirst());
        respuesta.put("ultima", turnos.isLast());
        return ResponseEntity.ok(respuesta);
    }

    private static BigDecimal aDecimal(Object valor) {
        if (valor == null || valor.toString().isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(valor.toString().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("El monto no es un número válido.");
        }
    }
}
