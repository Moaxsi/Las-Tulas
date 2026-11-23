package com.lastulas.ica.controller;

import com.lastulas.ica.DetalleVenta;
import com.lastulas.ica.DetalleVentaRepository;
import com.lastulas.ica.dto.VentaRequest;
import com.lastulas.ica.dto.VentaResponse;
import com.lastulas.ica.security.UsuarioPrincipal;
import com.lastulas.ica.service.FiltroVentas;
import com.lastulas.ica.service.ReporteService;
import com.lastulas.ica.service.VentaService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Puntos 4, 7, 10 y 11.
 *
 * POST /api/ventas                     cobra. El total, el IGV y el stock los
 *                                      pone el servidor.
 * POST /api/ventas/{id}/anular         anula con nota de credito. YA NO existe
 *                                      DELETE /api/ventas/{id}: anular no es borrar.
 * GET  /api/ventas                     pagina de ventas con los filtros del
 *                                      servidor. Devuelve {contenido, pagina,
 *                                      tamano, totalPaginas, totalElementos}.
 * GET  /api/ventas/resumen|por-dia|por-metodo-pago|cajas   agregaciones en SQL.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")
public class VentaController {

    /** Tope duro: sin esto, ?tamano=1000000 se come la memoria del servidor. */
    private static final int TAMANO_MAXIMO = 200;

    private final VentaService ventaService;
    private final ReporteService reporteService;
    private final DetalleVentaRepository detalleRepo;

    public VentaController(VentaService ventaService,
                           ReporteService reporteService,
                           DetalleVentaRepository detalleRepo) {
        this.ventaService = ventaService;
        this.reporteService = reporteService;
        this.detalleRepo = detalleRepo;
    }

    /**
     * El cajero cobra y el admin tambien (para cuando cubre una caja).
     * El id del cajero sale del token: por mucho que el body diga otro id,
     * la venta se atribuye a quien esta autenticado.
     */
    @PostMapping("/ventas")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public ResponseEntity<?> registrarVenta(@Valid @RequestBody VentaRequest req,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String claveRecibida,
                                           @AuthenticationPrincipal UsuarioPrincipal principal) {
        String claveIdempotencia = normalizarClave(claveRecibida);
        if (claveIdempotencia == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Falta una clave Idempotency-Key valida para este cobro."));
        }

        VentaResponse venta;
        try {
            venta = ventaService.registrarVenta(req, principal, claveIdempotencia);
        } catch (DataIntegrityViolationException e) {
            Optional<VentaResponse> repetida = ventaService.buscarVentaIdempotente(
                    req, principal, claveIdempotencia);
            if (repetida.isEmpty()) {
                throw e;
            }
            venta = repetida.get();
        }

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("success", true);
        respuesta.put("idVenta", venta.idVenta());
        respuesta.put("numeroComprobante", venta.numeroComprobante());
        respuesta.put("estado", venta.estado());
        respuesta.put("fechaVenta", venta.fechaVenta());
        respuesta.put("clienteNombre", venta.clienteNombre());
        respuesta.put("clienteDocumento", venta.clienteDocumento());
        respuesta.put("tipoComprobante", venta.tipoComprobante());
        respuesta.put("metodoPago", venta.metodoPago());
        respuesta.put("subtotal", venta.subtotal());
        respuesta.put("igv", venta.igv());
        respuesta.put("tasaIgv", venta.tasaIgv());
        respuesta.put("total", venta.total());
        respuesta.put("montoEfectivo", venta.montoEfectivo());
        respuesta.put("montoDigital", venta.montoDigital());
        respuesta.put("vuelto", venta.vuelto());
        respuesta.put("idUsuario", venta.idUsuario());
        respuesta.put("cajeroNombre", venta.cajeroNombre());
        respuesta.put("detalles", venta.detalles());
        return ResponseEntity.ok(respuesta);
    }

    private static String normalizarClave(String clave) {
        if (clave == null || clave.isBlank()) {
            return null;
        }
        String candidata = clave.trim().toLowerCase(Locale.ROOT);
        try {
            String normalizada = UUID.fromString(candidata).toString();
            return normalizada.equals(candidata) ? normalizada : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** ADMIN: ver el comprobante tal como quedo guardado. */
    @GetMapping("/ventas/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> obtenerVenta(@PathVariable Integer id) {
        return ResponseEntity.ok(ventaService.obtenerVenta(id));
    }

    /**
     * Punto 4. Pagina de ventas con filtro en el servidor.
     *
     * El parametro "caja" es el id_usuario, no el texto "Caja 3": el numero
     * depende del momento en que se armo la lista de cajeros y el id no.
     */
    @GetMapping("/ventas")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> listarVentas(
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "10") int tamano,
            @RequestParam(required = false) String buscar,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Integer caja,
            @RequestParam(required = false) String metodoPago,
            @RequestParam(required = false) String tipoComprobante,
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String numeroComprobante) {

        // valores absurdos no pueden romper el servidor ni devolverse al
        // cliente como un error de Spring feo
        int paginaSegura = Math.max(0, pagina);
        int tamanoSeguro = Math.min(Math.max(1, tamano), TAMANO_MAXIMO);

        FiltroVentas filtro = new FiltroVentas(buscar, desde, hasta, caja,
                metodoPago, tipoComprobante, estado, numeroComprobante);

        return ResponseEntity.ok(reporteService.listar(filtro,
                PageRequest.of(paginaSegura, tamanoSeguro, Sort.by(Sort.Direction.DESC, "idVenta"))));
    }

    /** KPIs de cabecera del reporte. Se calculan en SQL, no en el navegador. */
    @GetMapping("/ventas/resumen")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> resumen(@RequestParam(required = false) String buscar,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                     @RequestParam(required = false) Integer caja,
                                     @RequestParam(required = false) String metodoPago) {
        return ResponseEntity.ok(reporteService.resumen(
                new FiltroVentas(buscar, desde, hasta, caja, metodoPago, null, null, null)));
    }

    /** Serie diaria para el grafico. El navegador solo dibuja. */
    @GetMapping("/ventas/rendimiento-productos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> rendimientoProductos(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(reporteService.rendimientoProductos(desde, hasta));
    }

    @GetMapping("/ventas/por-dia")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> porDia(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                    @RequestParam(required = false) Integer caja) {
        return ResponseEntity.ok(reporteService.porDia(
                new FiltroVentas(null, desde, hasta, caja, null, null, null, null)));
    }

    /** Reparto por metodo de pago, agrupado por el codigo canonico (punto 7). */
    @GetMapping("/ventas/por-metodo-pago")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> porMetodoPago(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                           @RequestParam(required = false) Integer caja) {
        return ResponseEntity.ok(reporteService.porMetodoPago(
                new FiltroVentas(null, desde, hasta, caja, null, null, null, null)));
    }

    /** Totales por caja del periodo. */
    @GetMapping("/ventas/por-caja")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> porCaja(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                     @RequestParam(required = false) String metodoPago) {
        return ResponseEntity.ok(reporteService.porCaja(
                new FiltroVentas(null, desde, hasta, null, metodoPago, null, null, null)));
    }

    /** Catalogo de cajas para poblar el filtro. */
    @GetMapping("/ventas/cajas")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> cajas() {
        return reporteService.cajas();
    }

    @GetMapping("/ventas/{id}/detalle")
    @PreAuthorize("hasRole('ADMIN')")
    public List<DetalleVenta> obtenerDetalleVenta(@PathVariable Integer id) {
        return detalleRepo.findByIdVenta(id);
    }

    /**
     * Correccion administrativa del metodo de pago, para un cobro mal digitado.
     * Va al servicio para que sea transaccional y quede auditado (punto 12).
     */
    @PutMapping("/ventas/{id}/metodo-pago")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> modificarMetodoPago(@PathVariable Integer id,
                                                 @RequestBody Map<String, String> body,
                                                 @AuthenticationPrincipal UsuarioPrincipal principal) {
        String nuevoMetodo = body != null ? body.get("metodoPago") : null;
        return ResponseEntity.ok(ventaService.modificarMetodoPago(id, nuevoMetodo, principal));
    }

    /**
     * Punto 11: anular deja constancia. Reemplaza al viejo
     * DELETE /api/ventas/{id} que hacia ventaRepo.delete(v).
     */
    @PostMapping("/ventas/{id}/anular")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> anularVenta(@PathVariable Integer id,
                                        @RequestBody(required = false) Map<String, String> body,
                                        @AuthenticationPrincipal UsuarioPrincipal principal) {
        String motivo = body != null ? body.get("motivo") : null;
        return ResponseEntity.ok(ventaService.anularVenta(id, motivo, principal));
    }
}
