package com.lastulas.ica.service;

import com.lastulas.ica.EstadoVenta;
import com.lastulas.ica.MetodoPago;
import com.lastulas.ica.TipoComprobante;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.Venta;
import com.lastulas.ica.VentaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reportes de ventas con paginacion y agregaciones en la base (punto 4).
 *
 * El problema que se resuelve: GET /api/ventas devolvia la tabla completa y
 * las pantallas hacian el filtro, la suma y el promedio en JavaScript. Con un
 * mes de datos eran miles de filas en cada recarga, el navegador hacia el
 * trabajo que hace mejor la base, y los KPIs dependian de que llegaran todas.
 *
 * Ahora la consulta trae solo la pagina pedida (LIMIT/OFFSET) y los totales
 * salen de SUM y COUNT con los MISMOS filtros, de modo que el "total" que
 * muestra la cabecera siempre corresponde a lo que hay en la tabla.
 */
@Service
public class ReporteService {

    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final VentaRepository ventaRepo;
    private final EntityManager em;
    private final UsuarioHistorialService historialUsuarios;

    public ReporteService(VentaRepository ventaRepo, EntityManager em, UsuarioHistorialService historialUsuarios) {
        this.ventaRepo = ventaRepo;
        this.em = em;
        this.historialUsuarios = historialUsuarios;
    }

    // =================================================================
    // LISTA PAGINADA
    // =================================================================

    /** Solo lectura: ventas emitidas, con el precio final guardado por línea. */
    @Transactional(readOnly = true)
    public Map<String, Object> rendimientoProductos(LocalDate desde, LocalDate hasta) {
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la final.");
        }
        String sql = "SELECT d.id_producto, COALESCE(MAX(p.nombre_producto), MAX(d.nombre_producto)), "
                + "c.id_categoria, COALESCE(c.nombre, 'Sin categoría'), SUM(d.cantidad), SUM(d.subtotal) "
                + "FROM detalle_ventas d JOIN ventas v ON v.id_venta = d.id_venta "
                + "LEFT JOIN productos p ON p.id_producto = d.id_producto "
                + "LEFT JOIN categorias c ON c.id_categoria = p.id_categoria WHERE v.estado = :estado "
                + (desde != null ? "AND v.fecha_venta >= :desde " : "")
                + (hasta != null ? "AND v.fecha_venta < :hasta " : "")
                + "GROUP BY d.id_producto, c.id_categoria, c.nombre ORDER BY SUM(d.cantidad) DESC, SUM(d.subtotal) DESC, d.id_producto";
        var query = em.createNativeQuery(sql, Object[].class).setParameter("estado", "EMITIDA");
        if (desde != null) query.setParameter("desde", desde.atStartOfDay());
        if (hasta != null) query.setParameter("hasta", hasta.plusDays(1).atStartOfDay());
        List<?> filas = query.getResultList();
        return resumirRendimiento(filas.stream().map(f -> (Object[]) f).toList());
    }

    static Map<String, Object> resumirRendimiento(List<Object[]> filas) {
        BigDecimal total = filas.stream().map(f -> (BigDecimal) f[5]).reduce(CERO, BigDecimal::add);
        long unidades = filas.stream().mapToLong(f -> ((Number) f[4]).longValue()).sum();
        Map<Integer, Map<String, Object>> categorias = new LinkedHashMap<>();
        List<Map<String, Object>> productos = new ArrayList<>();
        for (Object[] f : filas) {
            long cantidad = ((Number) f[4]).longValue();
            BigDecimal monto = (BigDecimal) f[5];
            int categoriaId = f[2] == null ? -1 : ((Number) f[2]).intValue();
            Map<String, Object> categoria = categorias.computeIfAbsent(categoriaId, id -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("nombre", f[3]); c.put("monto", CERO); c.put("unidades", 0L); return c;
            });
            categoria.put("monto", ((BigDecimal) categoria.get("monto")).add(monto));
            categoria.put("unidades", (Long) categoria.get("unidades") + cantidad);
            Map<String, Object> producto = new LinkedHashMap<>();
            producto.put("idProducto", f[0]); producto.put("nombre", f[1]); producto.put("categoria", f[3]);
            producto.put("unidades", cantidad); producto.put("monto", monto);
            producto.put("porcentajeUnidades", unidades > 0 ? BigDecimal.valueOf(cantidad).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(unidades), 1, RoundingMode.HALF_UP) : CERO);
            producto.put("porcentajeVentas", porcentaje(monto, total));
            productos.add(producto);
        }
        List<Map<String, Object>> reparto = new ArrayList<>(categorias.values());
        reparto.forEach(c -> c.put("porcentaje", porcentaje((BigDecimal) c.get("monto"), total)));
        reparto.sort((a, b) -> ((BigDecimal) b.get("monto")).compareTo((BigDecimal) a.get("monto")));
        return Map.of("totalVentas", total, "totalUnidades", unidades, "categorias", reparto,
                "productos", productos.stream().limit(10).toList(), "totalProductos", productos.size());
    }

    private static BigDecimal porcentaje(BigDecimal parte, BigDecimal total) {
        return total.signum() > 0 ? parte.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP) : CERO;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listar(FiltroVentas filtro, Pageable pageable) {
        Specification<Venta> spec = aSpecification(filtro);
        Page<Venta> pagina = ventaRepo.findAll(spec, pageable);

        Map<Integer, Usuario> usuarios = usuariosPorId();
        Map<Integer, Integer> numerosDeCaja = numerosDeCaja(usuarios);

        List<Map<String, Object>> contenido = new ArrayList<>(pagina.getNumberOfElements());
        List<Integer> ids = new ArrayList<>();
        for (Venta v : pagina.getContent()) {
            contenido.add(aFila(v, usuarios, numerosDeCaja));
            ids.add(v.getIdVenta());
        }

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("contenido", contenido);
        respuesta.put("pagina", pagina.getNumber());
        respuesta.put("tamano", pagina.getSize());
        respuesta.put("totalPaginas", pagina.getTotalPages());
        respuesta.put("totalElementos", pagina.getTotalElements());
        respuesta.put("primera", pagina.isFirst());
        respuesta.put("ultima", pagina.isLast());
        respuesta.put("vacia", pagina.isEmpty());
        respuesta.put("totalesPagina", totalesDePagina(ids));
        return respuesta;
    }

    /**
     * Totales SOLO de la pagina visible. Se calculan aparte del resumen global
     * porque son cosas distintas: uno dice "cuanto vendimos en el periodo" y el
     * otro "cuanto suma lo que estas mirando". Confundirlos hacia que un
     * ADMIN creyera que el total del filtro era la mitad de la venta del dia.
     */
    private Map<String, Object> totalesDePagina(List<Integer> ids) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("efectivo", CERO);
        t.put("digital", CERO);
        t.put("total", CERO);
        if (ids.isEmpty()) {
            return t;
        }
        Object[] fila = ventaRepo.totalesDePagina(ids, EstadoVenta.EMITIDA);
        if (fila == null || fila.length < 3) {
            return t;
        }
        BigDecimal digital = dosDecimales(aDecimal(fila[2]));
        BigDecimal total = dosDecimales(aDecimal(fila[0]));
        t.put("efectivo", dosDecimales(aDecimal(fila[1])));
        t.put("digital", digital);
        t.put("total", total);
        return t;
    }

    // =================================================================
    // AGREGACIONES
    //
    // Las agregaciones reutilizan EXACTAMENTE el mismo Specification que la
    // lista. Antes cada consulta traia su propio WHERE con solo estado y
    // fecha, asi que el "total del filtro" de la cabecera no correspondia a las
    // filas de la tabla cuando se filtraba por cajero, metodo o numero de
    // boleta: el reporte-era y tabla-dime cosas distintas.
    // =================================================================

    /** KPIs de cabecera: emitidas y anuladas del periodo, con ticket promedio. */
    @Transactional(readOnly = true)
    public Map<String, Object> resumen(FiltroVentas filtro) {
        BigDecimal totalEmitido = CERO;
        long boletas = 0;
        BigDecimal efectivo = CERO;
        BigDecimal digital = CERO;

        Object[] f = agregar(conEstado(filtro, EstadoVenta.EMITIDA),
                (cb, root, selects) -> {
                    selects.add(cb.sum(root.get("total")));
                    selects.add(cb.count(root));
                    selects.add(cb.sum(root.get("montoEfectivo")));
                    selects.add(cb.sum(root.get("montoDigital")));
                });
        if (f != null && f.length >= 4) {
            totalEmitido = dosDecimales(aDecimal(f[0]));
            boletas = aEntero(f[1]);
            efectivo = dosDecimales(aDecimal(f[2]));
            digital = dosDecimales(aDecimal(f[3]));
        }

        long anuladas = 0;
        BigDecimal montoAnulado = CERO;
        Object[] fa = agregar(conEstado(filtro, EstadoVenta.ANULADA),
                (cb, root, selects) -> {
                    selects.add(cb.count(root));
                    selects.add(cb.sum(root.get("total")));
                });
        if (fa != null && fa.length >= 2) {
            anuladas = aEntero(fa[0]);
            montoAnulado = dosDecimales(aDecimal(fa[1]));
        }

        BigDecimal neto = dosDecimales(totalEmitido.subtract(montoAnulado));
        // El promedio se calcula con decimales y recien ahi se redondea. Con
        // enteros, S/ 33.33 entre 3 daba 11.11 y la suma de los promedios no
        // cuadraba con el total.
        BigDecimal ticketPromedio = boletas > 0
                ? totalEmitido.divide(BigDecimal.valueOf(boletas), 2, RoundingMode.HALF_UP)
                : CERO;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalEmitido", totalEmitido);
        m.put("boletasEmitidas", boletas);
        m.put("efectivo", efectivo);
        m.put("digital", digital);
        m.put("totalAnulado", montoAnulado);
        m.put("anuladas", anuladas);
        m.put("totalNeto", neto);
        m.put("ticketPromedio", ticketPromedio);
        m.put("participacionDigital", totalEmitido.signum() > 0
                ? digital.multiply(BigDecimal.valueOf(100))
                       .divide(totalEmitido, 1, RoundingMode.HALF_UP)
                : CERO);
        return m;
    }

    /** Serie diaria para el grafico. El navegador solo dibuja los puntos. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> porDia(FiltroVentas filtro) {
        LocalDate hoy = LocalDate.now();
        LocalDate desde = filtro.desde() != null ? filtro.desde() : hoy.minusDays(6);
        LocalDate hasta = filtro.hasta() != null ? filtro.hasta() : hoy;
        List<Object[]> filas = ventaRepo.totalesDiarios(
                EstadoVenta.EMITIDA.name(),
                desde.atStartOfDay(),
                hasta.plusDays(1).atStartOfDay());

        List<Map<String, Object>> serie = new ArrayList<>(filas.size());
        for (Object[] f : filas) {
            Map<String, Object> punto = new LinkedHashMap<>();
            punto.put("fecha", String.valueOf(f[0]));
            punto.put("total", dosDecimales(aDecimal(f[1])));
            punto.put("cantidad", aEntero(f[2]));
            serie.add(punto);
        }
        return serie;
    }

    /**
     * Reparto por metodo de pago. Se agrupa por el codigo canonico (punto 7),
     * asi que "Efectivo" y "EFECTIVO" ya no son dos filas distintas: la
     * migracion 03 normalizo los datos viejos y el enum evita que vuelvan a
     * aparecer.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> porMetodoPago(FiltroVentas filtro) {
        List<Object[]> filas = agregarAgrupado(
                conEstado(filtro, EstadoVenta.EMITIDA),
                (cb, root) -> root.get("metodoPago"),
                (cb, root, selects) -> {
                    selects.add(root.get("metodoPago"));
                    selects.add(cb.sum(root.get("total")));
                    selects.add(cb.count(root));
                });

        BigDecimal total = CERO;
        for (Object[] f : filas) {
            total = total.add(aDecimal(f[1]));
        }

        List<Map<String, Object>> reparto = new ArrayList<>(filas.size());
        for (Object[] f : filas) {
            MetodoPago metodo = MetodoPago.desde(String.valueOf(f[0]));
            BigDecimal monto = dosDecimales(aDecimal(f[1]));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("codigo", metodo.name());
            m.put("nombre", metodo.getNombre());
            m.put("total", monto);
            m.put("cantidad", aEntero(f[2]));
            m.put("porcentaje", total.signum() > 0
                    ? monto.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP));
            reparto.add(m);
        }
        return reparto;
    }

    /** Totales por caja. El arqueo propiamente dicho vive en CajaTurnoService. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> porCaja(FiltroVentas filtro) {
        List<Object[]> filas = agregarAgrupado(
                conEstado(filtro, EstadoVenta.EMITIDA),
                (cb, root) -> root.get("idUsuario"),
                (cb, root, selects) -> {
                    selects.add(root.get("idUsuario"));
                    selects.add(cb.count(root));
                    selects.add(cb.sum(root.get("total")));
                    selects.add(cb.sum(root.get("montoEfectivo")));
                    selects.add(cb.sum(root.get("montoDigital")));
                });

        Map<Integer, Usuario> usuarios = usuariosPorId();
        Map<Integer, Integer> numerosDeCaja = numerosDeCaja(usuarios);

        List<Map<String, Object>> cajas = new ArrayList<>(filas.size());
        for (Object[] f : filas) {
            Integer idUsuario = f[0] instanceof Number n ? n.intValue() : null;
            Usuario u = idUsuario != null ? usuarios.get(idUsuario) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idUsuario", idUsuario);
            m.put("cajaNumero", numerosDeCaja.get(idUsuario));
            m.put("cajeroNombre", u != null ? u.getNombreCompleto() : "Sin asignar");
            m.put("cantidad", aEntero(f[1]));
            m.put("total", dosDecimales(aDecimal(f[2])));
            m.put("efectivo", dosDecimales(aDecimal(f[3])));
            m.put("digital", dosDecimales(aDecimal(f[4])));
            cajas.add(m);
        }
        cajas.sort((a, b) -> {
            Integer na = (Integer) a.get("cajaNumero");
            Integer nb = (Integer) b.get("cajaNumero");
            int va = na != null ? na : Integer.MAX_VALUE;
            int vb = nb != null ? nb : Integer.MAX_VALUE;
            return Integer.compare(va, vb);
        });
        return cajas;
    }

    /**
     * Catalogo de cajas para el filtro. Se genera de la lista ORDENADA de
     * usuarios, igual que en la venta: el "Caja N" sale siempre del mismo
     * lado.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cajas() {
        Map<Integer, Usuario> usuarios = usuariosPorId();
        Map<Integer, Integer> numeros = numerosDeCaja(usuarios);

        List<Map<String, Object>> lista = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : numeros.entrySet()) {
            Usuario u = usuarios.get(e.getKey());
            if (u == null || u.getRol() == null || !"CAJERO".equalsIgnoreCase(u.getRol().getNombre())
                    || !"Activo".equalsIgnoreCase(u.getEstado())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idUsuario", e.getKey());
            m.put("cajaNumero", e.getValue());
            m.put("nombre", u != null ? u.getNombreCompleto() : "Cajero #" + e.getKey());
            lista.add(m);
        }
        lista.sort((a, b) -> Integer.compare((Integer) a.get("cajaNumero"), (Integer) b.get("cajaNumero")));
        return lista;
    }

    // =================================================================
    // APOYO
    // =================================================================

    /** Copia del filtro con el estado forzado (los reportes miran solo emitidas). */
    private static FiltroVentas conEstado(FiltroVentas f, EstadoVenta estado) {
        return new FiltroVentas(
                f.buscar(), f.desde(), f.hasta(), f.idUsuario(),
                f.metodoPago(), f.tipoComprobante(), estado.name(), f.numeroComprobante());
    }

    /** Columnas de la agregacion (SUM, COUNT, ...). */
    @FunctionalInterface
    private interface Selector {
        void aplicar(CriteriaBuilder cb, Root<Venta> root, List<Expression<?>> selects);
    }

    /** Columna por la que se agrupa. */
    @FunctionalInterface
    private interface Agrupador {
        Expression<?> columna(CriteriaBuilder cb, Root<Venta> root);
    }

    /**
     * Agregacion de una sola fila.
     *
     * El WHERE sale del MISMO Specification que usa la lista paginada, que es
     * lo que garantiza que el total de la cabecera y las filas de la tabla
     * hablen del mismo conjunto. Las consultas con su propio WHERE duplicaban
     * los criterios y se quedaban fuera a la mitad.
     */
    private Object[] agregar(FiltroVentas filtro, Selector selector) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Venta> root = cq.from(Venta.class);

        List<Expression<?>> selects = new ArrayList<>();
        selector.aplicar(cb, root, selects);

        cq.select(cb.array(selects.toArray(new Expression[0])));
        cq.where(predicado(filtro, root, cq, cb));
        return em.createQuery(cq).getSingleResult();
    }

    private List<Object[]> agregarAgrupado(FiltroVentas filtro, Agrupador agrupador, Selector selector) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Venta> root = cq.from(Venta.class);

        List<Expression<?>> selects = new ArrayList<>();
        selector.aplicar(cb, root, selects);

        Expression<?> columna = agrupador.columna(cb, root);
        cq.select(cb.array(selects.toArray(new Expression[0])));
        cq.where(predicado(filtro, root, cq, cb));
        cq.groupBy(columna);
        cq.orderBy(cb.asc(columna));
        return em.createQuery(cq).getResultList();
    }

    private Predicate predicado(FiltroVentas filtro, Root<Venta> root, CriteriaQuery<?> cq, CriteriaBuilder cb) {
        Predicate p = aSpecification(filtro).toPredicate(root, cq, cb);
        return p != null ? p : cb.conjunction();
    }

    /**
     * Criterios del WHERE. Se arma con la Criteria API y no concatenando
     * texto JPQL: los filtros llegan del query string y una cadena armada a
     * mano seria un SQL injection esperando a que alguien mande una comilla.
     */
    private Specification<Venta> aSpecification(FiltroVentas f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            if (f.idUsuario() != null && f.idUsuario() > 0) {
                ps.add(cb.equal(root.get("idUsuario"), f.idUsuario()));
            }

            EstadoVenta estado = f.estadoNormalizado();
            if (estado != null) {
                ps.add(cb.equal(root.get("estado"), estado));
            }

            if (f.metodoPago() != null && !f.metodoPago().isBlank()) {
                // Se compara contra el codigo canonico, no contra el texto que
                // el usuario eligio en el desplegable.
                ps.add(cb.equal(root.get("metodoPago"),
                        MetodoPago.desde(f.metodoPago()).name()));
            }

            if (f.tipoComprobante() != null && !f.tipoComprobante().isBlank()) {
                ps.add(cb.equal(root.get("tipoComprobante"),
                        TipoComprobante.desde(f.tipoComprobante()).name()));
            }

            Expression<String> comprobante = numeroCompleto(root, cb);
            if (f.numeroComprobante() != null && !f.numeroComprobante().isBlank()) {
                // Se busca contra "B001-000012" y no solo contra "B001": si el
                // admin escribia el numero entero de la boleta en el campo,
                // antes no encontraba nada.
                ps.add(cb.like(cb.lower(comprobante), "%"
                        + f.numeroComprobante().trim().toLowerCase(Locale.ROOT) + "%"));
            }

            if (f.buscar() != null && !f.buscar().isBlank()) {
                String q = "%" + f.buscar().trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(comprobante), q),
                        cb.like(cb.lower(root.get("clienteNombre")), q),
                        cb.like(cb.lower(cb.coalesce(root.get("clienteDocumento"), "")), q)));
            }

            // Rango de fechas: [desde 00:00, hasta+1 00:00). Con el "hasta"
            // exclusivo se incluyen TODAS las ventas del ultimo dia. Con
            // "<= hasta 00:00" se perdia todo lo que se cobro despues de
            // medianoche, que es la mitad del turno de un local que cierra tarde.
            LocalDateTime desde = desdeDe(f);
            LocalDateTime hasta = hastaDe(f);
            if (desde != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("fechaVenta"), desde));
            }
            if (hasta != null) {
                ps.add(cb.lessThan(root.get("fechaVenta"), hasta));
            }

            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /**
     * "B001-000012" como una sola expresion. numeroCorrelativo es entero y
     * numeroSerie es texto, asi que hace falta el as(String.class) para poder
     * concatenarlos; concat solo acepta dos operandos, de ahi el anidamiento.
     */
    private static Expression<String> numeroCompleto(Root<Venta> root, CriteriaBuilder cb) {
        return cb.concat(
                cb.concat(root.get("numeroSerie").as(String.class), cb.literal("-")),
                root.get("numeroCorrelativo").as(String.class));
    }

    private static LocalDateTime desdeDe(FiltroVentas f) {
        return f.desde() != null ? f.desde().atStartOfDay() : null;
    }

    private static LocalDateTime hastaDe(FiltroVentas f) {
        return f.hasta() != null ? f.hasta().plusDays(1).atStartOfDay() : null;
    }

    private Map<Integer, Usuario> usuariosPorId() {
        return historialUsuarios.porId();
    }

    /**
     * "Caja N" sale de la lista ORDENADA de cajeros, nunca de iterar un HashMap:
     * el orden de un HashMap no esta garantizado y dos pantallas llegarian a
     * numeros distintos para la misma persona.
     */
    private Map<Integer, Integer> numerosDeCaja(Map<Integer, Usuario> usuarios) {
        List<Usuario> ordenados = new ArrayList<>(usuarios.values());
        ordenados.sort((a, b) -> Integer.compare(a.getIdUsuario(), b.getIdUsuario()));

        Map<Integer, Integer> posicion = new HashMap<>();
        int contador = 0;
        for (Usuario u : ordenados) {
            if (u.getRol() != null && "CAJERO".equalsIgnoreCase(u.getRol().getNombre())) {
                posicion.put(u.getIdUsuario(), ++contador);
            }
        }
        // Un ADMIN que cubre una caja no tiene posicion propia: se numera por
        // su id_usuario, que es estable entre recargas.
        for (Usuario u : ordenados) {
            if (!posicion.containsKey(u.getIdUsuario())) {
                posicion.put(u.getIdUsuario(), u.getIdUsuario());
            }
        }
        return posicion;
    }

    private static Map<String, Object> aFila(Venta v, Map<Integer, Usuario> usuarios,
                                             Map<Integer, Integer> numerosDeCaja) {
        Usuario u = v.getIdUsuario() != null ? usuarios.get(v.getIdUsuario()) : null;
        Integer numeroCaja = v.getIdUsuario() != null ? numerosDeCaja.get(v.getIdUsuario()) : null;

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("idVenta", v.getIdVenta());
        map.put("numeroComprobante", v.getNumeroComprobante());
        map.put("idUsuario", v.getIdUsuario());
        map.put("cajaNumero", numeroCaja);
        map.put("cajaNombre", "Caja general");
        map.put("cajeroNombre", u != null ? u.getNombreCompleto() : "Sin asignar");
        map.put("clienteNombre", v.getClienteNombre());
        map.put("clienteDocumento", v.getClienteDocumento());
        // Codigo canonico + etiqueta: el codigo es lo que agrupa el reporte.
        map.put("tipoComprobante", v.getTipoComprobante());
        map.put("tipoComprobanteNombre", TipoComprobante.desde(v.getTipoComprobante()).getNombre());
        map.put("metodoPago", v.getMetodoPago());
        map.put("metodoPagoNombre", MetodoPago.desde(v.getMetodoPago()).getNombre());
        map.put("subtotal", v.getSubtotal());
        map.put("igv", v.getIgv());
        map.put("total", v.getTotal());
        map.put("montoEfectivo", v.getMontoEfectivo());
        map.put("montoDigital", v.getMontoDigital());
        map.put("vuelto", v.getVuelto());
        map.put("fechaVenta", v.getFechaVenta() != null ? v.getFechaVenta().toString() : null);
        map.put("estado", v.getEstado().name());
        return map;
    }

    private static BigDecimal dosDecimales(BigDecimal valor) {
        return valor != null ? valor.setScale(2, RoundingMode.HALF_UP) : CERO;
    }

    private static BigDecimal aDecimal(Object valor) {
        if (valor == null) return BigDecimal.ZERO;
        if (valor instanceof BigDecimal d) return d;
        if (valor instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private static Integer aEntero(Object valor) {
        if (valor == null) return 0;
        if (valor instanceof Number n) return n.intValue();
        return 0;
    }
}
