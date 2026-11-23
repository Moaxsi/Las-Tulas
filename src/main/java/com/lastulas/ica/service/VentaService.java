package com.lastulas.ica.service;

import com.lastulas.ica.Auditoria;
import com.lastulas.ica.AdicionalCobrado;
import com.lastulas.ica.Categoria;
import com.lastulas.ica.DetalleVenta;
import com.lastulas.ica.DetalleVentaRepository;
import com.lastulas.ica.EstadoVenta;
import com.lastulas.ica.MetodoPago;
import com.lastulas.ica.NotaCredito;
import com.lastulas.ica.NotaCreditoRepository;
import com.lastulas.ica.Producto;
import com.lastulas.ica.ProductoRepository;
import com.lastulas.ica.TipoComprobante;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import com.lastulas.ica.Venta;
import com.lastulas.ica.VentaRepository;
import com.lastulas.ica.dto.ItemVentaRequest;
import com.lastulas.ica.dto.VentaRequest;
import com.lastulas.ica.dto.VentaResponse;
import com.lastulas.ica.security.UsuarioPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class VentaService {

    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final VentaRepository ventaRepo;
    private final DetalleVentaRepository detalleRepo;
    private final ProductoRepository productoRepo;
    private final UsuarioRepository usuarioRepo;
    private final NotaCreditoRepository notaCreditoRepo;
    private final IgvService igvService;
    private final ToppingCatalogoService toppingsCatalogo;
    private final CajaTurnoService cajaTurnoService;
    private final AuditoriaService auditoria;
    private final UsuarioHistorialService historialUsuarios;

    public VentaService(VentaRepository ventaRepo,
                        DetalleVentaRepository detalleRepo,
                        ProductoRepository productoRepo,
                        UsuarioRepository usuarioRepo,
                        NotaCreditoRepository notaCreditoRepo,
                        IgvService igvService,
                        ToppingCatalogoService toppingsCatalogo,
                        CajaTurnoService cajaTurnoService,
                        AuditoriaService auditoria, UsuarioHistorialService historialUsuarios) {
        this.ventaRepo = ventaRepo;
        this.detalleRepo = detalleRepo;
        this.productoRepo = productoRepo;
        this.usuarioRepo = usuarioRepo;
        this.notaCreditoRepo = notaCreditoRepo;
        this.igvService = igvService;
        this.toppingsCatalogo = toppingsCatalogo;
        this.cajaTurnoService = cajaTurnoService;
        this.auditoria = auditoria;
        this.historialUsuarios = historialUsuarios;
    }

    // =====================================================
    // PUNTO 10 - Cobro: el servidor es la fuente del dinero
    // =====================================================

    /**
     * Registra la venta completa en UNA transaccion. O entra todo (venta,
     * detalle y descuento de stock) o no entra nada.
     *
     * El navegador solo dice QUE pidio. Los precios salen de la tabla
     * productos, el total se suma aqui, el IGV se desarma aqui y el stock
     * baja aqui. El cuerpo del POST no puede influenciar ninguno de esos
     * cuatro numeros.
     */
    @Transactional
    public VentaResponse registrarVenta(VentaRequest req, UsuarioPrincipal principal) {
        return registrarVenta(req, principal, null);
    }

    @Transactional
    public VentaResponse registrarVenta(VentaRequest req, UsuarioPrincipal principal,
                                        String claveIdempotencia) {
        String huellaIdempotencia = null;
        if (claveIdempotencia != null) {
            if (claveIdempotencia.isBlank()) {
                throw new BusinessException("La clave de idempotencia no puede estar vacia.");
            }
            huellaIdempotencia = calcularHuella(req);
            Optional<VentaResponse> existente = buscarRespuestaIdempotente(
                    claveIdempotencia, huellaIdempotencia, principal);
            if (existente.isPresent()) {
                return existente.get();
            }
        }

        // La misma exclusión que el cierre: un cobro confirmado pertenece
        // completo al turno abierto y nunca aparece después de su arqueo.
        var turnoCaja = cajaTurnoService.turnoAbierto(principal.getIdUsuario())
                .orElseThrow(() -> new BusinessException("La caja general está cerrada. Pide al administrador que la abra."));

        List<ItemVentaRequest> items = req.items();
        // Validar antes de descontar stock, tambien en llamadas internas al servicio.
        items.forEach(item -> normalizarNota(item.notas()));

        // --- 1. Una sola consulta para todo el catalero de la venta. ---------
        // Pedir los productos de a uno seria el patron N+1: una consulta por
        // linea, una por topping. Aqui van todos los ids en un solo IN.
        Set<Integer> idsRequeridos = new LinkedHashSet<>();
        for (ItemVentaRequest item : items) {
            idsRequeridos.add(item.idProducto());
            if (item.toppings() != null) {
                idsRequeridos.addAll(item.toppings());
            }
            if (item.sabores() != null) idsRequeridos.addAll(item.sabores());
            if (item.frutas() != null) idsRequeridos.addAll(item.frutas());
        }

        Map<Integer, Producto> catalogo = new HashMap<>();
        productoRepo.findByIdIn(new ArrayList<>(idsRequeridos))
                    .forEach(p -> catalogo.put(p.getIdProducto(), p));

        for (Integer id : idsRequeridos) {
            if (!catalogo.containsKey(id)) {
                throw new BusinessException("El producto con id " + id + " no existe en el catalogo.");
            }
        }

        // --- 1b. Puntos 5 y 6: los toppings tienen que existir en la carta. -
        // findByIdIn trae topping y base juntos, asi que la validacion no
        // cuesta consultas extra. Si el POS y el backend no coinciden en la
        // combinacion, aqui es donde se corta.
        Map<Integer, Categoria> categorias = toppingsCatalogo.validarRecetas(catalogo, lineasDe(items));

        // --- 1c. Un producto dado de baja no se puede cobrar (punto 9). -----
        // La baja logica saca el producto del POS, pero un POS con cache
        // viejita o un fetch a mano podrian ofrecerlo igual.
        for (Map.Entry<Integer, Producto> entry : catalogo.entrySet()) {
            if (!entry.getValue().estaActivo()) {
                throw new BusinessException('"' + entry.getValue().getNombreProducto()
                        + "\" esta dado de baja y no se puede cobrar.");
            }
        }

        // --- 2. Calcular el total con los precios de la base de datos. -------
        Map<Integer, Integer> unidadesPorProducto = new LinkedHashMap<>();
        List<DetalleVenta> detalles = new ArrayList<>();
        BigDecimal total = CERO;

        for (ItemVentaRequest item : items) {
            Producto base = catalogo.get(item.idProducto());
            int cantidad = item.cantidad();

            BigDecimal precioToppings = CERO;
            List<String> nombresToppings = new ArrayList<>();
            List<String> idsToppings = new ArrayList<>();
            List<AdicionalCobrado> adicionalesCobrados = new ArrayList<>();

            List<Integer> toppings = item.toppings() == null ? List.of() : item.toppings();
            Categoria categoria = categorias.get(base.getIdCategoria());
            boolean ramen = categoria != null && "RAMEN".equalsIgnoreCase(categoria.getNombre().trim());
            Integer preparacion = ramen ? toppings.stream()
                    .filter(id -> esPreparacionConTresToppings(catalogo.get(id)))
                    .findFirst().orElse(null) : null;
            int incluidos = ramen ? (preparacion == null ? 0 : 3)
                    : toppingsIncluidosPara(base, categoria);
            for (Integer idTopping : toppings) {
                Producto topping = catalogo.get(idTopping);
                // La preparacion se cobra siempre y no consume uno de sus tres toppings.
                if (idTopping.equals(preparacion) || incluidos-- <= 0) {
                    precioToppings = precioToppings.add(topping.getPrecio());
                    if (topping.getPrecio().signum() > 0) {
                        adicionalesCobrados.add(new AdicionalCobrado(idTopping, topping.getNombreProducto(),
                                IgvService.dosDecimales(topping.getPrecio())));
                    }
                }
                nombresToppings.add(topping.getNombreProducto());
                idsToppings.add(String.valueOf(idTopping));
                unidadesPorProducto.merge(idTopping, cantidad, VentaService::sumarUnidades);
            }

            // El precio unitario ya incluye los toppings del renglon, para que
            // precioUnitario x cantidad sea exactamente igual al subtotal. Asi
            // los numeros del ticket cuadran solos al revisarlos.
            BigDecimal precioFrutas = CERO;
            int frutasIncluidas = base.getFrutasIncluidas() == null ? 0 : base.getFrutasIncluidas();
            List<String> nombresFrutas = new ArrayList<>();
            List<String> idsFrutas = new ArrayList<>();
            for (Integer id : item.frutas() == null ? List.<Integer>of() : item.frutas()) {
                Producto fruta = catalogo.get(id);
                if (frutasIncluidas-- <= 0) {
                    precioFrutas = precioFrutas.add(fruta.getPrecio());
                    if (fruta.getPrecio().signum() > 0) {
                        adicionalesCobrados.add(new AdicionalCobrado(id, fruta.getNombreProducto(),
                                IgvService.dosDecimales(fruta.getPrecio())));
                    }
                }
                nombresFrutas.add(fruta.getNombreProducto());
                idsFrutas.add(id.toString());
                unidadesPorProducto.merge(id, cantidad, VentaService::sumarUnidades);
            }
            BigDecimal precioUnitario = base.getPrecio().add(precioToppings).add(precioFrutas);
            BigDecimal subtotal = precioUnitario.multiply(BigDecimal.valueOf(cantidad));

            total = total.add(subtotal);
            unidadesPorProducto.merge(base.getIdProducto(), cantidad, VentaService::sumarUnidades);

            DetalleVenta det = new DetalleVenta();
            det.setIdProducto(base.getIdProducto());
            det.setNombreProducto(base.getNombreProducto());
            det.setNotas(normalizarNota(item.notas()));
            det.setCantidad(cantidad);
            det.setPrecioUnitario(IgvService.dosDecimales(precioUnitario));
            det.setSubtotal(IgvService.dosDecimales(subtotal));
            det.setPrecioBaseUnitario(IgvService.dosDecimales(base.getPrecio()));
            det.setAdicionalesCobrados(adicionalesCobrados);
            det.setFrutasDetalle(String.join(", ", nombresFrutas));
            det.setFrutasIds(String.join(",", idsFrutas));
            det.setToppingsDetalle(String.join(", ", nombresToppings));
            det.setToppingsIds(String.join(",", idsToppings));
            List<String> nombresSabores = new ArrayList<>();
            List<String> idsSabores = new ArrayList<>();
            for (Integer idSabor : item.sabores() == null ? List.<Integer>of() : item.sabores()) {
                Producto sabor = catalogo.get(idSabor);
                // Los sabores distinguen el producto sin modificar su precio.
                nombresSabores.add(sabor.getNombreProducto());
                idsSabores.add(String.valueOf(idSabor));
                unidadesPorProducto.merge(idSabor, cantidad, VentaService::sumarUnidades);
            }
            det.setSaboresDetalle(String.join(", ", nombresSabores));
            det.setSaboresIds(String.join(",", idsSabores));
            detalles.add(det);
        }

        total = IgvService.dosDecimales(total);

        // --- 3. Desglose fiscal (punto 12). -------------------------------
        BigDecimal tasa = igvService.tasaVigente();
        BigDecimal subtotalBase = igvService.aBaseImponible(total, tasa);
        BigDecimal igv = igvService.calcularIgv(total, subtotalBase);

        // --- 4. Validar el pago. -------------------------------------------
        BigDecimal efectivo = nz(req.montoEfectivo());
        BigDecimal digital = nz(req.montoDigital());
        BigDecimal recibido = efectivo.add(digital);

        if (recibido.compareTo(total) < 0) {
            throw new BusinessException(
                    "El pago no cubre el total. Faltan S/ " + IgvService.dosDecimales(total.subtract(recibido)));
        }

        BigDecimal vuelto = IgvService.dosDecimales(recibido.subtract(total));

        // El vuelto no es ingreso: se descuenta del efectivo registrado para
        // que el monto de la venta sea exactamente lo que se facturo.
        BigDecimal efectivoRegistrado = efectivo;
        BigDecimal digitalRegistrado = digital;
        if (vuelto.signum() > 0) {
            BigDecimal aDescontarDelEfectivo = vuelto.min(efectivo);
            efectivoRegistrado = efectivo.subtract(aDescontarDelEfectivo);
            digitalRegistrado = digital.subtract(vuelto.subtract(aDescontarDelEfectivo));
        }

        // --- 5. Descontar stock ANTES de escribir la venta. -----------------
        // descontarStock() es un UPDATE condicional (stock >= cantidad), asi que
        // si dos cajeros compiten por la ultima unidad solo uno gana. Si
        // devuelve 0 lanzo excepcion y la transaccion entera se revierte.
        for (Map.Entry<Integer, Integer> entry : unidadesPorProducto.entrySet()) {
            int actualizado = productoRepo.descontarStock(entry.getKey(), entry.getValue());
            if (actualizado == 0) {
                Producto p = productoRepo.findById(entry.getKey()).orElse(null);
                String nombre = p != null ? p.getNombreProducto() : "producto " + entry.getKey();
                int disponible = p != null && p.getStock() != null ? p.getStock() : 0;
                throw new BusinessException(
                        "No hay stock suficiente de \"" + nombre + "\". Pedido: " + entry.getValue()
                        + ", disponible: " + disponible + ".");
            }
        }

        // --- 6. Guardar la venta con el id del cajero que viene del token. --
        Venta venta = new Venta();
        // El correlativo se pide a la secuencia y se escribe explicito, no se
        // deja al DEFAULT de la columna. Si se dejara, la base pondria un
        // numero, Hibernate no lo releeria y el ticket impreso al cajero
        // caeria en el id de la venta: un numero distinto al guardado.
        TipoComprobante comprobante = TipoComprobante.desde(req.tipoComprobante());
        boolean notaVenta = comprobante == TipoComprobante.NOTA_VENTA;
        Long correlativoVenta = notaVenta
                ? ventaRepo.siguienteCorrelativoNotaVenta() : ventaRepo.siguienteCorrelativo();
        venta.setNumeroSerie(notaVenta ? "NV01" : "B001");
        venta.setNumeroCorrelativo(correlativoVenta != null ? correlativoVenta.intValue() : 1);
        // La fecha tambien se escribe explicito (ya no la pone el DEFAULT),
        // asi que la que se responde al cajero es la que quedo guardada.
        venta.setFechaVenta(LocalDateTime.now());
        venta.setClienteNombre(textoPorDefecto(req.clienteNombre(), "CLIENTE GENERAL"));
        venta.setClienteDocumento(textoPorDefecto(req.clienteDocumento(), ""));
        // Punto 7: se guarda el CODIGO canonico, no la etiqueta que mando el
        // navegador. "EFECTIVO" y "Efectivo" tienen que acabar en la misma
        // columna o el reporte por metodo de pago los cuenta por separado.
        // La etiqueta se reconstruye al mostrar con metodoPago.getNombre().
        MetodoPago metodo = MetodoPago.desde(req.metodoPago());
        validarDocumento(comprobante, venta.getClienteDocumento());
        venta.setTipoComprobante(comprobante.name());
        venta.setMetodoPago(metodo.name());
        venta.setIdUsuario(principal.getIdUsuario());
        venta.setSubtotal(subtotalBase);
        venta.setIgv(igv);
        venta.setTasaIgv(tasa);
        venta.setTotal(total);
        venta.setMontoEfectivo(IgvService.dosDecimales(efectivoRegistrado));
        venta.setMontoDigital(IgvService.dosDecimales(digitalRegistrado));
        venta.setVuelto(vuelto);
        venta.setClaveIdempotencia(claveIdempotencia);
        venta.setHuellaIdempotencia(huellaIdempotencia);

        // Punto 11: la venta queda colgando del turno abierto del cajero. Si no
        // hay turno abierto no es un error: el cobro es valido igual, pero esa
        // venta no entra en ningun arqueo y queda con id_caja_turno NULL.
        venta.setIdCajaTurno(turnoCaja.getIdCajaTurno());

        Venta guardada = ventaRepo.save(venta);

        for (DetalleVenta det : detalles) {
            det.setIdVenta(guardada.getIdVenta());
        }
        detalleRepo.saveAll(detalles);

        // Punto 12: queda registrado quien cobro, cuanto y como. Importa
        // porque el arqueo de caja despues compara contra esta fila.
        auditoria.registrar(Auditoria.TABLA_VENTAS, guardada.getIdVenta(), "CREAR",
                null,
                guardada.getNumeroComprobante() + ", total=" + guardada.getTotal()
                        + ", metodo=" + metodo.name() + ", comprobante=" + comprobante.name(),
                principal.getNombreCompleto() + " registro la venta", principal);

        return aResponse(guardada, detalles, tasa, principal.getNombreCompleto());
    }

    @Transactional(readOnly = true)
    public Optional<VentaResponse> buscarVentaIdempotente(VentaRequest req, UsuarioPrincipal principal,
                                                           String claveIdempotencia) {
        return buscarRespuestaIdempotente(claveIdempotencia, calcularHuella(req), principal);
    }

    private Optional<VentaResponse> buscarRespuestaIdempotente(String claveIdempotencia,
                                                                String huella,
                                                                UsuarioPrincipal principal) {
        Optional<Venta> ventaExistente = ventaRepo.findByClaveIdempotencia(claveIdempotencia);
        if (ventaExistente.isEmpty()) {
            return Optional.empty();
        }
        Venta venta = ventaExistente.get();
        if (!Objects.equals(venta.getIdUsuario(), principal.getIdUsuario())
                || !Objects.equals(venta.getHuellaIdempotencia(), huella)) {
            throw new BusinessException(
                    "La clave de cobro ya se uso con otra cuenta o con otros datos.", 409);
        }

        List<DetalleVenta> detalles = detalleRepo.findByIdVenta(venta.getIdVenta());
        return Optional.of(aResponse(venta, detalles, igvService.tasaVigente(),
                principal.getNombreCompleto()));
    }

    private static String calcularHuella(VentaRequest req) {
        try {
            StringBuilder contenido = new StringBuilder();
            agregarCampo(contenido, req.clienteNombre());
            agregarCampo(contenido, req.clienteDocumento());
            agregarCampo(contenido, req.tipoComprobante());
            agregarCampo(contenido, req.metodoPago());
            agregarCampo(contenido, decimalCanonico(req.montoEfectivo()));
            agregarCampo(contenido, decimalCanonico(req.montoDigital()));
            agregarCampo(contenido, Integer.toString(req.items().size()));
            for (ItemVentaRequest item : req.items()) {
                agregarCampo(contenido, String.valueOf(item.idProducto()));
                agregarCampo(contenido, String.valueOf(item.cantidad()));
                List<Integer> toppings = item.toppings();
                agregarCampo(contenido, toppings == null ? null : Integer.toString(toppings.size()));
                if (toppings != null) {
                    for (Integer topping : toppings) {
                        agregarCampo(contenido, String.valueOf(topping));
                    }
                }
            }
            // Conservar las huellas anteriores para reintentos sin sabores.
            if (req.items().stream().anyMatch(item -> item.sabores() != null && !item.sabores().isEmpty())) {
                agregarCampo(contenido, "sabores-v1");
                for (ItemVentaRequest item : req.items()) {
                    List<Integer> sabores = item.sabores() == null ? List.of() : item.sabores();
                    agregarCampo(contenido, Integer.toString(sabores.size()));
                    sabores.forEach(id -> agregarCampo(contenido, String.valueOf(id)));
                }
            }
            // Mantener compatibles las claves anteriores cuando no hay notas.
            if (req.items().stream().anyMatch(item -> normalizarNota(item.notas()) != null)) {
                agregarCampo(contenido, "notas-v1");
                req.items().forEach(item -> agregarCampo(contenido, normalizarNota(item.notas())));
            }
            if (req.items().stream().anyMatch(item -> item.frutas() != null && !item.frutas().isEmpty())) {
                agregarCampo(contenido, "frutas-v1");
                req.items().forEach(item -> {
                    List<Integer> ids = item.frutas() == null ? List.of() : item.frutas();
                    agregarCampo(contenido, Integer.toString(ids.size()));
                    ids.forEach(id -> agregarCampo(contenido, id.toString()));
                });
            }
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(contenido.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No se pudo calcular la huella del cobro.", e);
        }
    }

    private static void agregarCampo(StringBuilder contenido, String valor) {
        if (valor == null) {
            contenido.append("-1:");
        } else {
            contenido.append(valor.length()).append(':').append(valor);
        }
    }

    private static String normalizarNota(String nota) {
        if (nota == null) return null;
        String limpia = nota.replace("\r\n", "\n").strip();
        if (limpia.length() > 300) throw new BusinessException("La nota admite hasta 300 caracteres.");
        return limpia.isEmpty() ? null : limpia;
    }

    private static String decimalCanonico(BigDecimal valor) {
        return valor == null ? null : valor.stripTrailingZeros().toPlainString();
    }

    /** Punto 7: no se emite un comprobante al que le falta el documento que exige. */
    private static void validarDocumento(TipoComprobante comprobante, String documento) {
        String doc = documento != null ? documento.trim() : "";
        if (doc.isEmpty()) {
            if (comprobante.exigeDocumento()) {
                throw new BusinessException("Una " + comprobante.getNombre()
                        + " necesita el " + (comprobante.esFactura() ? "RUC" : "DNI") + " del cliente.");
            }
            return;
        }
        if (comprobante.esFactura() && (doc.length() != 11 || !doc.chars().allMatch(Character::isDigit))) {
            throw new BusinessException("El RUC debe tener 11 digitos. Se recibio: \"" + doc + "\".");
        }
    }

    private static int toppingsIncluidosPara(Producto producto, Categoria categoria) {
        String nombreCategoria = normalizarNombreCupo(categoria == null ? null : categoria.getNombre());
        if (Set.of("MILK TEA", "MILK FRAPPE", "FRUIT FRAPPE", "FRUIT TEA", "COCTEL BOBA").contains(nombreCategoria)) {
            String nombre = normalizarNombreCupo(producto.getNombreProducto())
                    .replaceAll("( (PETIT|REGULAR|R|G))?( *\\((12|16) *OZ\\))?$", "");
            return Set.of("MANGO MATCHA LATTE", "STRAWBERRY MATCHA LATTE", "OREO FRAPPE", "STRAWBERRY TARO LATTE")
                    .contains(nombre) ? 0 : 1;
        }
        return producto.getToppingsIncluidos() == null ? 0 : producto.getToppingsIncluidos();
    }

    private static String normalizarNombreCupo(String nombre) {
        return Normalizer.normalize(Objects.toString(nombre, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static boolean esPreparacionConTresToppings(Producto topping) {
        String nombre = Normalizer.normalize(Objects.toString(topping.getNombreProducto(), ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+|\\s+", "").toLowerCase(Locale.ROOT);
        return nombre.equals("preparacion") || nombre.equals("preparacion+3toppings");
    }

    /** Traduce los items del request al formato que espera el validador de toppings. */
    private static List<ToppingCatalogoService.LineaVenta> lineasDe(List<ItemVentaRequest> items) {
        List<ToppingCatalogoService.LineaVenta> lineas = new ArrayList<>(items.size());
        for (ItemVentaRequest item : items) {
            lineas.add(new ToppingCatalogoService.LineaVenta(
                    item.idProducto(), item.toppings(), item.sabores(), item.frutas()));
        }
        return lineas;
    }

    // =====================================================
    // PUNTO 11 - Anulacion con nota de credito (nunca DELETE)
    // =====================================================

    /**
     * Anula una venta. La fila NO se borra: pasa a estado ANULADA, se deja la
     * nota de credito y se devuelve el stock. Borrar el comprobante esta
     * prohibido para efectos fiscales.
     */
    @Transactional
    public Map<String, Object> anularVenta(Integer idVenta, String motivo, UsuarioPrincipal principal) {
        Venta venta = ventaRepo.findById(idVenta)
                .orElseThrow(() -> BusinessException.noEncontrado("La venta"));

        if (!venta.getEstado().estaEmitida()) {
            throw new BusinessException(
                    "La venta " + venta.getNumeroComprobante() + " ya fue anulada. No se puede anular dos veces.");
        }

        if (motivo == null || motivo.isBlank()) {
            throw new BusinessException("Indica el motivo de la anulacion.");
        }

        List<DetalleVenta> detalles = detalleRepo.findByIdVenta(idVenta);

        // Devolver el stock. Los toppings tambien vuelven, y se puede hacer
        // gracias a toppings_ids: sin esa columna solo tendríamos sus nombres.
        Map<Integer, Integer> unidadesADevolver = new LinkedHashMap<>();
        for (DetalleVenta det : detalles) {
            if (det.getIdProducto() != null) {
                unidadesADevolver.merge(det.getIdProducto(), det.getCantidad(), VentaService::sumarUnidades);
            }
            for (Integer idTopping : idsToppings(det)) {
                unidadesADevolver.merge(idTopping, det.getCantidad(), VentaService::sumarUnidades);
            }
            if (det.getFrutasIds() != null && !det.getFrutasIds().isBlank()) {
                for (String id : det.getFrutasIds().split(",")) {
                    unidadesADevolver.merge(Integer.valueOf(id.trim()), det.getCantidad(), VentaService::sumarUnidades);
                }
            }
            if (det.getSaboresIds() != null && !det.getSaboresIds().isBlank()) {
                for (String idSabor : det.getSaboresIds().split(",")) {
                    unidadesADevolver.merge(Integer.valueOf(idSabor.trim()), det.getCantidad(), VentaService::sumarUnidades);
                }
            }
        }
        for (Map.Entry<Integer, Integer> entry : unidadesADevolver.entrySet()) {
            productoRepo.devolverStock(entry.getKey(), entry.getValue());
        }

        // Constancia de la anulacion.
        NotaCredito nota = new NotaCredito();
        nota.setIdVenta(idVenta);
        nota.setNumeroNotaCredito(siguienteNumeroNotaCredito());
        nota.setTipoComprobante("Nota de credito");
        nota.setMotivo(motivo.trim());
        nota.setTotalAnulado(venta.getTotal());
        nota.setFechaAnulacion(LocalDateTime.now());
        nota.setIdUsuarioAnulacion(principal.getIdUsuario());
        notaCreditoRepo.save(nota);

        venta.setEstado(EstadoVenta.ANULADA);
        venta.setFechaAnulacion(nota.getFechaAnulacion());
        venta.setMotivoAnulacion(nota.getMotivo());
        venta.setIdUsuarioAnulacion(principal.getIdUsuario());
        ventaRepo.save(venta);

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("success", true);
        respuesta.put("idVenta", idVenta);
        respuesta.put("numeroComprobante", venta.getNumeroComprobante());
        respuesta.put("estado", venta.getEstado().name());
        respuesta.put("numeroNotaCredito", nota.getNumeroNotaCredito());
        respuesta.put("totalAnulado", venta.getTotal());
        respuesta.put("stockDevuelto", unidadesADevolver.entrySet().stream()
                .map(e -> Map.<String, Object>of("idProducto", e.getKey(), "unidades", e.getValue()))
                .toList());

        // Punto 12. Se guarda el estado ANTERIOR y el nuevo: sin el anterior
        // el historial no dice que paso, solo como quedo.
        auditoria.registrar(Auditoria.TABLA_VENTAS, idVenta, "ANULAR",
                EstadoVenta.EMITIDA.name(),
                EstadoVenta.ANULADA.name() + ", nota=" + nota.getNumeroNotaCredito(),
                motivo.trim(), principal);

        return respuesta;
    }

    /**
     * Correccion administrativa del metodo de pago (para un cobro mal
     * digitado). Se movio aca desde el controlador porque es una escritura que
     * necesita transaccion y auditoria, igual que el resto.
     */
    @Transactional
    public Map<String, Object> modificarMetodoPago(Integer idVenta, String metodoTexto,
                                                   UsuarioPrincipal principal) {
        if (metodoTexto == null || metodoTexto.isBlank()) {
            throw new BusinessException("Indica el metodo de pago.");
        }
        Venta venta = ventaRepo.findById(idVenta)
                .orElseThrow(() -> BusinessException.noEncontrado("La venta"));

        MetodoPago nuevo = MetodoPago.desde(metodoTexto);
        String anterior = venta.getMetodoPago();
        if (nuevo.name().equals(anterior)) {
            return Map.of("success", true, "message", "El metodo de pago ya era " + nuevo.getNombre());
        }

        venta.setMetodoPago(nuevo.name());
        ventaRepo.save(venta);

        auditoria.registrar(Auditoria.TABLA_VENTAS, idVenta, "MODIFICAR",
                "metodo_pago=" + anterior,
                "metodo_pago=" + nuevo.name(),
                principal.getNombreCompleto() + " corrigio el metodo de pago", principal);

        return Map.of("success", true, "message", "Metodo de pago modificado a " + nuevo.getNombre());
    }

    private String siguienteNumeroNotaCredito() {
        Long correlativo = notaCreditoRepo.siguienteCorrelativo();
        return "BC01-" + String.format("%06d", correlativo != null ? correlativo : 1L);
    }

    private static String resumirAdicionales(String detalle, String ids) {
        if (detalle == null || detalle.isBlank() || ids == null || ids.isBlank()) return detalle;
        String[] nombres = detalle.split(", ", -1);
        String[] productos = ids.split(",");
        // Conservar el texto original si un nombre contiene comas o faltan ids.
        if (nombres.length != productos.length) return detalle;
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        Map<String, String> etiquetas = new LinkedHashMap<>();
        for (int i = 0; i < productos.length; i++) {
            String id = productos[i].trim();
            cantidades.merge(id, 1, Integer::sum);
            etiquetas.putIfAbsent(id, nombres[i]);
        }
        List<String> resumen = new ArrayList<>();
        cantidades.forEach((id, cantidad) -> resumen.add(cantidad > 1 ? "x" + cantidad + " " + etiquetas.get(id) : etiquetas.get(id)));
        return String.join(", ", resumen);
    }

    private List<Integer> idsToppings(DetalleVenta det) {
        List<Integer> ids = new ArrayList<>();
        if (det.getToppingsIds() == null || det.getToppingsIds().isBlank()) {
            return ids;
        }
        for (String parte : det.getToppingsIds().split(",")) {
            try {
                ids.add(Integer.parseInt(parte.trim()));
            } catch (NumberFormatException ignored) {
                // Un id ilegible no debe impedir la anulacion de la venta.
            }
        }
        return ids;
    }

    // =====================================================
    // Consultas de lectura para el POS y los reportes
    // =====================================================

    @Transactional(readOnly = true)
    public VentaResponse obtenerVenta(Integer idVenta) {
        Venta venta = ventaRepo.findById(idVenta)
                .orElseThrow(() -> BusinessException.noEncontrado("La venta"));

        List<DetalleVenta> detalles = detalleRepo.findByIdVenta(idVenta);
        Usuario cajero = venta.getIdUsuario() != null
                ? usuarioRepo.findById(venta.getIdUsuario()).orElse(null)
                : null;

        return aResponse(venta, detalles, igvService.tasaVigente(),
                cajero != null ? cajero.getNombreCompleto() : historialUsuarios.nombre(venta.getIdUsuario()));
    }

    private VentaResponse aResponse(Venta venta, Collection<DetalleVenta> detalles,
                                    BigDecimal tasa, String cajeroNombre) {
        List<VentaResponse.DetalleResponse> items = detalles.stream()
                .map(d -> new VentaResponse.DetalleResponse(
                        d.getIdDetalle(),
                        d.getIdProducto(),
                        d.getNombreProducto(),
                        d.getCantidad(),
                        d.getPrecioUnitario(),
                        d.getSubtotal(),
                        resumirAdicionales(d.getToppingsDetalle(), d.getToppingsIds()),
                        resumirAdicionales(d.getSaboresDetalle(), d.getSaboresIds()),
                        d.getNotas(), resumirAdicionales(d.getFrutasDetalle(), d.getFrutasIds()),
                        d.getPrecioBaseUnitario(), d.getAdicionalesCobrados()))
                .toList();

        // ---------------------------------------------------------------
        // Punto 7: en la respuesta se manda el codigo Y la etiqueta. El codigo
        // es lo que se guarda y lo que agrupa el reporte; la etiqueta es lo
        // que el ticket tiene que mostrar. Si solo se mandara el codigo, el
        // ticket imprimiria "EFECTIVO" donde antes decia "Efectivo".
        // ---------------------------------------------------------------
        return new VentaResponse(
                venta.getIdVenta(),
                venta.getNumeroComprobante(),
                venta.getEstado().name(),
                venta.getFechaVenta(),
                venta.getClienteNombre(),
                venta.getClienteDocumento(),
                venta.getTipoComprobante(),
                MetodoPago.desde(venta.getMetodoPago()).getNombre(),
                venta.getSubtotal(),
                venta.getIgv(),
                venta.getTasaIgv() != null ? venta.getTasaIgv() : tasa,
                venta.getTotal(),
                venta.getMontoEfectivo(),
                venta.getMontoDigital(),
                venta.getVuelto(),
                venta.getIdUsuario(),
                cajeroNombre,
                items);
    }

    private static BigDecimal nz(BigDecimal valor) {
        return valor != null ? valor : BigDecimal.ZERO;
    }

    private static Integer sumarUnidades(Integer a, Integer b) {
        return (a != null ? a : 0) + (b != null ? b : 0);
    }

    private static String textoPorDefecto(String valor, String porDefecto) {
        return valor == null || valor.isBlank() ? porDefecto : valor.trim();
    }
}
