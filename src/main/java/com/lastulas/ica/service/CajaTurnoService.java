package com.lastulas.ica.service;

import com.lastulas.ica.Auditoria;
import com.lastulas.ica.AuditoriaRepository;
import com.lastulas.ica.CajaTurno;
import com.lastulas.ica.CajaTurnoRepository;
import com.lastulas.ica.EstadoVenta;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import com.lastulas.ica.security.UsuarioPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Apertura, cierre y arqueo de caja (punto 11).
 *
 * Antes no habia turno: el cajero entra, cobra y se va, y nadie comparaba el
 * efectivo declarado contra el contado. Aqui el arqueo se CALCULA:
 *
 *   esperado  = monto_inicial + SUM(monto_efectivo) de las ventas del turno
 *   diferencia = efectivo_contado - esperado
 *
 * Que sea un calculo y no un campo que escribe el cajero es lo unico que
 * hace que un error de caja se detecte. Si el turno lo declarara el mismo
 * cajero, el arqueo siempre daria 0.
 */
@Service
public class CajaTurnoService {

    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final CajaTurnoRepository cajaRepo;
    private final UsuarioRepository usuarioRepo;
    private final AuditoriaService auditoria;
    private final AuditoriaRepository auditoriaRepo;
    private final CajaOperacionLock cajaLock;

    public CajaTurnoService(CajaTurnoRepository cajaRepo,
                            UsuarioRepository usuarioRepo,
                            AuditoriaService auditoria, AuditoriaRepository auditoriaRepo, CajaOperacionLock cajaLock) {
        this.cajaRepo = cajaRepo;
        this.usuarioRepo = usuarioRepo;
        this.auditoria = auditoria;
        this.auditoriaRepo = auditoriaRepo;
        this.cajaLock = cajaLock;
    }

    /** La venta adquiere el bloqueo antes de descontar stock y lo conserva hasta el commit. */
    @Transactional
    public Optional<CajaTurno> turnoAbierto(Integer idUsuario) {
        cajaLock.adquirir();
        return abiertoGeneral();
    }

    private Optional<CajaTurno> abiertoGeneral() {
        List<CajaTurno> abiertos = cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA);
        if (abiertos.size() > 1) {
            throw new BusinessException("Hay varios turnos anteriores abiertos. Cierra los turnos pendientes antes de usar la caja general.", 409);
        }
        return abiertos.stream().findFirst();
    }

    @Transactional
    public Map<String, Object> abrirTurno(BigDecimal montoInicial, UsuarioPrincipal principal) {
        return abrirTurno(montoInicial, principal.getIdUsuario(), principal);
    }

    @Transactional
    public Map<String, Object> abrirTurno(BigDecimal montoInicial, Integer idUsuario, UsuarioPrincipal principal) {
        exigirAdministrador(principal);
        cajaLock.adquirir();
        if (abiertoGeneral().isPresent()) {
            throw new BusinessException("La caja general ya tiene un turno abierto. Ciérralo antes de abrir otro.");
        }
        // El responsable es quien abre, no un cajero elegido en el navegador.
        idUsuario = principal.getIdUsuario();

        BigDecimal inicial = montoInicial != null ? dosDecimales(montoInicial) : CERO;
        if (montoInicial != null && montoInicial.signum() < 0) {
            throw new BusinessException("El monto inicial no puede ser negativo.");
        }

        validarLimiteMonetario(montoInicial);
        CajaTurno turno = new CajaTurno();
        turno.setIdUsuario(idUsuario);
        turno.setFechaApertura(LocalDateTime.now());
        turno.setMontoInicial(inicial);
        turno.setEstado(CajaTurno.ABIERTA);
        CajaTurno guardado = cajaRepo.save(turno);

        auditoria.registrar(Auditoria.TABLA_CAJAS, guardado.getIdCajaTurno(), "ABRIR_CAJA",
                null, "monto_inicial=" + inicial,
                principal.getNombreCompleto() + " abrió la caja general para todos los cajeros", principal);

        return detalleConTotales(guardado, idUsuario);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> turnoActual(UsuarioPrincipal principal) {
        return turnoActual(principal.getIdUsuario(), principal);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> turnoActual(Integer idUsuario, UsuarioPrincipal principal) {
        List<CajaTurno> abiertos = cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA);
        boolean admin = "ADMIN".equalsIgnoreCase(principal.getRol());
        if (abiertos.size() > 1) {
            if (!admin) return Map.of("abierto", false, "requiereRegularizacion", true);
            return Map.of("abierto", false, "requiereRegularizacion", true,
                    "turnosPendientes", abiertos.stream().map(t -> detalleConTotales(t, t.getIdUsuario())).toList());
        }
        if (abiertos.isEmpty()) return Map.of("abierto", false);
        CajaTurno turno = abiertos.getFirst();
        // El cajero consulta sus ventas; el arqueo general pertenece al admin.
        if (!admin) return Map.of("abierto", true, "idCajaTurno", turno.getIdCajaTurno());
        return detalleConTotales(turno, turno.getIdUsuario());
    }

    /**
     * Cierra el turno abierto. El efectivo contado es obligatorio: un cierre
     * sin arqueo no es un cierre, es solo borrar el turno.
     */
    @Transactional
    public Map<String, Object> cerrarTurno(BigDecimal efectivoContado, String observaciones,
                                           UsuarioPrincipal principal) {
        return cerrarTurno(efectivoContado, observaciones, BigDecimal.ZERO, null, principal.getIdUsuario(), principal);
    }

    @Transactional
    public Map<String, Object> cerrarTurno(BigDecimal efectivoContado, String observaciones,
                                          BigDecimal aporte, String motivoAporte, Integer idUsuario,
                                          UsuarioPrincipal principal) {
        exigirAdministrador(principal);
        if (efectivoContado == null) {
            throw new BusinessException("Indica cuanto efectivo contaste en la gaveta.");
        }
        if (efectivoContado.signum() < 0) {
            throw new BusinessException("El efectivo contado no puede ser negativo.");
        }

        validarLimiteMonetario(efectivoContado);
        if (aporte != null && aporte.signum() < 0) throw new BusinessException("El aporte no puede ser negativo.");
        BigDecimal extra = dosDecimales(aporte);
        if (extra.signum() > 0 && (motivoAporte == null || motivoAporte.isBlank())) {
            throw new BusinessException("Explica el motivo del aporte de efectivo.");
        }
        cajaLock.adquirir();
        CajaTurno turno = abiertoGeneral()
                .orElseThrow(() -> new BusinessException("No hay un turno de caja abierto."));

        aplicarTotales(turno);

        BigDecimal original = dosDecimales(efectivoContado);
        BigDecimal contado = dosDecimales(original.add(extra));
        BigDecimal diferencia = dosDecimales(contado.subtract(esperado(turno)));

        String detalle = observaciones == null ? "" : observaciones.trim();
        if (extra.signum() > 0) {
            if (original.compareTo(esperado(turno)) >= 0) {
                throw new BusinessException("Solo agrega un aporte cuando falta efectivo en la caja.");
            }
            if (extra.compareTo(esperado(turno).subtract(original)) > 0) {
                throw new BusinessException("El aporte no puede superar el efectivo que falta.");
            }
            detalle = "Aporte S/ " + extra + "; contado antes S/ " + original
                    + "; diferencia previa S/ " + original.subtract(esperado(turno))
                    + ". Motivo: " + motivoAporte.trim() + (detalle.isEmpty() ? "" : "\n" + detalle);
        }
        if (detalle.length() > 300) throw new BusinessException("Reduce el motivo y las observaciones: juntos admiten hasta 300 caracteres, incluido el resumen del aporte.");
        if (diferencia.signum() != 0 && detalle.isBlank()) {
            throw new BusinessException("Explica la diferencia de caja en las observaciones antes de cerrar.");
        }

        turno.setEfectivoContado(contado);
        turno.setDiferencia(diferencia);
        turno.setObservaciones(detalle.isEmpty() ? null : detalle);
        turno.setFechaCierre(LocalDateTime.now());
        turno.setEstado(CajaTurno.CERRADA);
        cajaRepo.save(turno);

        auditoria.registrar(Auditoria.TABLA_CAJAS, turno.getIdCajaTurno(), "CERRAR_CAJA",
                "ABIERTA; efectivo antes del aporte=" + original,
                "efectivo_contado=" + contado + ", aporte=" + extra + ", diferencia=" + diferencia,
                principal.getNombreCompleto() + " cerró la caja general" + ". " + detalle, principal);

        return detalleConTotales(turno, turno.getIdUsuario());
    }

    @Transactional
    public Map<String, Object> cerrarCajaGeneral(BigDecimal contado, String nota, Integer idTurno, UsuarioPrincipal principal) {
        exigirAdministrador(principal);
        cajaLock.adquirir();
        CajaTurno turno = abiertoGeneral().orElseThrow(() -> new BusinessException("La caja general está cerrada."));
        if (!turno.getIdCajaTurno().equals(idTurno)) {
            throw new BusinessException("La caja cambió. Actualiza la pantalla y vuelve a contar el efectivo.", 409);
        }
        return cerrarTurno(contado, nota, principal);
    }

    private static void exigirAdministrador(UsuarioPrincipal principal) {
        if (principal == null || !"ADMIN".equalsIgnoreCase(principal.getRol())) {
            throw new BusinessException("Solo el administrador puede gestionar la caja general.", 403);
        }
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> historial(Integer idUsuario, Pageable pageable) {
        Page<CajaTurno> pagina = (idUsuario != null && idUsuario > 0)
                ? cajaRepo.findByIdUsuarioOrderByFechaAperturaDesc(idUsuario, pageable)
                : cajaRepo.findAllByOrderByFechaAperturaDesc(pageable);
        return pagina.map(t -> detalleConTotales(t, t.getIdUsuario()));
    }

    /**
     * Rellena los totales del turno con SUM sobre sus ventas, pero SOLO si el
     * turno sigue abierto.
     *
     * Al cerrar se guardan los totales en la fila (ver cerrar) y a partir de ahi
     * son un snapshot: si un arqueo viejo se recalculara en cada consulta,
     * anular una venta de hace tres meses cambiaria el total del turno que ya
     * estaba cerrado y el descuadre de ese dia dejaria de ser el descuadre real.
     */
    private void aplicarTotales(CajaTurno turno) {
        if (CajaTurno.CERRADA.equalsIgnoreCase(turno.getEstado())) {
            return;
        }
        CajaTurnoRepository.TotalesTurno fila = cajaRepo.totalesDelTurno(turno.getIdCajaTurno(), EstadoVenta.EMITIDA);
        if (fila == null) {
            turno.setTotalVentas(CERO);
            turno.setTotalBoletas(0);
            turno.setTotalEfectivo(CERO);
            turno.setTotalDigital(CERO);
            return;
        }
        turno.setTotalVentas(dosDecimales(fila.getTotalVentas()));
        turno.setTotalBoletas(fila.getTotalBoletas() == null ? 0 : Math.toIntExact(fila.getTotalBoletas()));
        turno.setTotalEfectivo(dosDecimales(fila.getTotalEfectivo()));
        turno.setTotalDigital(dosDecimales(fila.getTotalDigital()));
    }

    /** Mapa listo para JSON con el turno y su arqueo. */
    private Map<String, Object> detalleConTotales(CajaTurno turno, Integer idUsuario) {
        aplicarTotales(turno);

        Usuario u = idUsuario != null
                ? usuarioRepo.findById(idUsuario).orElse(null) : null;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idCajaTurno", turno.getIdCajaTurno());
        m.put("idUsuario", turno.getIdUsuario());
        m.put("cajeroNombre", u != null ? u.getNombreCompleto() : "Sin asignar");
        m.put("estado", turno.getEstado());
        m.put("abierto", turno.estaAbierta());
        m.put("fechaApertura", turno.getFechaApertura() != null ? turno.getFechaApertura().toString() : null);
        m.put("fechaCierre", turno.getFechaCierre() != null ? turno.getFechaCierre().toString() : null);
        m.put("montoInicial", turno.getMontoInicial());
        m.put("totalVentas", turno.getTotalVentas());
        m.put("totalBoletas", turno.getTotalBoletas());
        m.put("totalEfectivo", turno.getTotalEfectivo());
        m.put("totalDigital", turno.getTotalDigital());
        m.put("efectivoEsperado", dosDecimales(esperado(turno)));
        m.put("efectivoContado", turno.getEfectivoContado());
        m.put("diferencia", turno.getDiferencia());
        m.put("observaciones", turno.getObservaciones());
        List<Auditoria> registros = auditoriaRepo.findByTablaAndIdRegistroOrderByFechaDesc(Auditoria.TABLA_CAJAS, turno.getIdCajaTurno());
        registros.stream().filter(a -> "TRANSFERIR_CUSTODIA".equals(a.getAccion())).findFirst()
                .ifPresent(a -> m.put("cajeroNombre", a.getValorAnterior()));
        m.put("movimientos", registros.stream().filter(a -> esMovimiento(a.getAccion())).map(a -> {
            Map<String, Object> movimiento = new LinkedHashMap<>();
            movimiento.put("id", a.getIdAuditoria());
            movimiento.put("tipo", a.getAccion());
            movimiento.put("monto", new BigDecimal(a.getValorNuevo()));
            movimiento.put("motivo", a.getDetalle());
            movimiento.put("responsable", a.getUsuarioNombre());
            movimiento.put("fecha", a.getFecha());
            return movimiento;
        }).toList());
        return m;
    }

    /** Movimientos monetarios inmutables, escritos de forma obligatoria en la misma transacción. */
    @Transactional
    public Map<String, Object> registrarMovimiento(String tipo, BigDecimal monto, String motivo,
                                                  Integer idCajaTurno, String claveOperacion, UsuarioPrincipal principal) {
        exigirAdministrador(principal);
        if (!esMovimiento(tipo)) throw new BusinessException("Selecciona un tipo de movimiento válido.");
        if (monto == null || monto.signum() <= 0 || monto.scale() > 2 || monto.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new BusinessException("Ingresa un monto mayor a cero, con un máximo de dos decimales.");
        }
        if (motivo == null || motivo.isBlank() || motivo.trim().length() > 300) {
            throw new BusinessException("Explica el motivo del movimiento (máximo 300 caracteres).");
        }
        if (claveOperacion == null || !claveOperacion.matches("[a-fA-F0-9-]{36}")) throw new BusinessException("Falta la referencia del movimiento. Abre el formulario de nuevo.");
        cajaLock.adquirir();
        CajaTurno turno = abiertoGeneral().orElseThrow(() -> new BusinessException("La caja general está cerrada."));
        if (!turno.getIdCajaTurno().equals(idCajaTurno)) throw new BusinessException("La caja cambió. Actualiza la pantalla antes de registrar dinero.", 409);
        String referencia = "operacion=" + claveOperacion + ";";
        for (Auditoria anterior : auditoriaRepo.findByTablaAndIdRegistroOrderByFechaDesc(Auditoria.TABLA_CAJAS, idCajaTurno)) {
            if (esMovimiento(anterior.getAccion()) && anterior.getValorAnterior() != null && anterior.getValorAnterior().startsWith(referencia)) {
                if (!tipo.equals(anterior.getAccion()) || monto.compareTo(new BigDecimal(anterior.getValorNuevo())) != 0
                        || !motivo.trim().equals(anterior.getDetalle())) throw new BusinessException("Este movimiento ya fue registrado con otros datos.", 409);
                return detalleConTotales(turno, turno.getIdUsuario());
            }
        }
        aplicarTotales(turno);
        if ("RETIRO_CAJA".equals(tipo) && monto.compareTo(esperado(turno)) > 0) {
            throw new BusinessException("El retiro supera el efectivo esperado en la caja.");
        }
        Auditoria movimiento = new Auditoria();
        movimiento.setTabla(Auditoria.TABLA_CAJAS);
        movimiento.setIdRegistro(turno.getIdCajaTurno());
        movimiento.setAccion(tipo);
        movimiento.setValorAnterior(referencia + "esperado=" + esperado(turno).toPlainString());
        movimiento.setValorNuevo(dosDecimales(monto).toPlainString());
        movimiento.setDetalle(motivo.trim());
        movimiento.setIdUsuario(principal.getIdUsuario());
        movimiento.setUsuarioNombre(principal.getNombreCompleto());
        auditoriaRepo.saveAndFlush(movimiento);
        return detalleConTotales(turno, turno.getIdUsuario());
    }

    /** Permite cerrar los turnos del sistema anterior, con conteo real y nota obligatoria. */
    @Transactional
    public Map<String, Object> cerrarAnterior(Integer id, BigDecimal contado, String nota, UsuarioPrincipal principal) {
        exigirAdministrador(principal);
        cajaLock.adquirir();
        if (cajaRepo.findByEstadoOrderByFechaAperturaAsc(CajaTurno.ABIERTA).size() < 2) {
            throw new BusinessException("Usa el cierre de la caja general para el turno actual.", 409);
        }
        CajaTurno turno = cajaRepo.findById(id).orElseThrow(() -> BusinessException.noEncontrado("El turno"));
        if (!turno.estaAbierta()) throw new BusinessException("El turno ya está cerrado.");
        if (contado == null || contado.signum() < 0 || nota == null || nota.isBlank() || nota.length() > 300) {
            throw new BusinessException("Indica el efectivo contado y una nota de cierre de hasta 300 caracteres.");
        }
        validarLimiteMonetario(contado);
        aplicarTotales(turno);
        turno.setEfectivoContado(dosDecimales(contado));
        turno.setDiferencia(dosDecimales(contado.subtract(esperado(turno))));
        turno.setObservaciones(nota.trim());
        turno.setFechaCierre(LocalDateTime.now());
        turno.setEstado(CajaTurno.CERRADA);
        cajaRepo.save(turno);
        auditoria.registrar(Auditoria.TABLA_CAJAS, id, "CERRAR_CAJA", "ABIERTA", "CERRADA",
                "Cierre de turno anterior: " + nota, principal);
        return detalleConTotales(turno, turno.getIdUsuario());
    }

    private BigDecimal esperado(CajaTurno turno) {
        BigDecimal neto = CERO;
        for (Auditoria a : auditoriaRepo.findByTablaAndIdRegistroOrderByFechaDesc(Auditoria.TABLA_CAJAS, turno.getIdCajaTurno())) {
            if ("INGRESO_CAJA".equals(a.getAccion())) neto = neto.add(new BigDecimal(a.getValorNuevo()));
            if ("RETIRO_CAJA".equals(a.getAccion())) neto = neto.subtract(new BigDecimal(a.getValorNuevo()));
        }
        // Una reposición cubre dinero faltante, no aumenta lo que ya debía haber.
        return turno.efectivoEsperado().add(neto);
    }

    private static boolean esMovimiento(String tipo) {
        return "INGRESO_CAJA".equals(tipo) || "RETIRO_CAJA".equals(tipo) || "REPOSICION_CAJA".equals(tipo);
    }

    private static void validarLimiteMonetario(BigDecimal monto) {
        if (monto != null && (monto.scale() > 2 || monto.compareTo(new BigDecimal("99999999.99")) > 0)) {
            throw new BusinessException("El monto admite hasta S/ 99,999,999.99 y un máximo de dos decimales.");
        }
    }

    private static BigDecimal dosDecimales(BigDecimal valor) {
        if (valor == null) {
            return CERO;
        }
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

}
