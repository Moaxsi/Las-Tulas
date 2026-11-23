package com.lastulas.ica.service;

import com.lastulas.ica.Categoria;
import com.lastulas.ica.CategoriaRepository;
import com.lastulas.ica.DetalleVenta;
import com.lastulas.ica.DetalleVentaRepository;
import com.lastulas.ica.EstadoVenta;
import com.lastulas.ica.NotaCredito;
import com.lastulas.ica.NotaCreditoRepository;
import com.lastulas.ica.ParametroRepository;
import com.lastulas.ica.Producto;
import com.lastulas.ica.ProductoRepository;
import com.lastulas.ica.UsuarioRepository;
import com.lastulas.ica.Venta;
import com.lastulas.ica.VentaRepository;
import com.lastulas.ica.dto.ItemVentaRequest;
import com.lastulas.ica.dto.VentaRequest;
import com.lastulas.ica.dto.VentaResponse;
import com.lastulas.ica.security.UsuarioPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Estas pruebas fijan las garantias del punto 10 y del punto 11. Son las que
 * hmbran si alguien "optimiza" el servicio y vuelve a confiar en el navegador.
 */
class VentaServiceTest {

    private VentaRepository ventaRepo;
    private DetalleVentaRepository detalleRepo;
    private ProductoRepository productoRepo;
    private NotaCreditoRepository notaCreditoRepo;
    private VentaService servicio;
    private UsuarioPrincipal cajero;
    private CajaTurnoService cajaTurno;
    private ToppingCatalogoService toppingsCatalogo;

    @BeforeEach
    void setUp() {
        ventaRepo = mock(VentaRepository.class);
        detalleRepo = mock(DetalleVentaRepository.class);
        productoRepo = mock(ProductoRepository.class);
        notaCreditoRepo = mock(NotaCreditoRepository.class);
        UsuarioRepository usuarioRepo = mock(UsuarioRepository.class);

        ParametroRepository parametroRepo = mock(ParametroRepository.class);
        when(parametroRepo.findById(any())).thenReturn(Optional.empty());
        IgvService igv = new IgvService(parametroRepo, "0.18");

        // Los tres collaborators nuevos van mockeados para que estas pruebas
        // sigan midiendo lo que median antes: los precios y el stock. Cada uno
        // tiene su propia prueba en su propia clase.
        toppingsCatalogo = mock(ToppingCatalogoService.class);
        when(toppingsCatalogo.validarRecetas(any(), anyList())).thenReturn(Map.of());
        cajaTurno = mock(CajaTurnoService.class);
        AuditoriaService auditoria = mock(AuditoriaService.class);

        com.lastulas.ica.CajaTurno turno = new com.lastulas.ica.CajaTurno();
        turno.setIdCajaTurno(9);
        turno.setIdUsuario(1); // Lo abrió el admin, no el cajero que cobra.
        when(cajaTurno.turnoAbierto(anyInt())).thenReturn(Optional.of(turno));

        servicio = new VentaService(ventaRepo, detalleRepo, productoRepo, usuarioRepo,
                notaCreditoRepo, igv, toppingsCatalogo, cajaTurno, auditoria, mock(UsuarioHistorialService.class));

        when(ventaRepo.save(any(Venta.class))).thenAnswer(inv -> {
            Venta v = inv.getArgument(0);
            v.setIdVenta(77);
            return v;
        });
        when(notaCreditoRepo.siguienteCorrelativo()).thenReturn(1L);

        cajero = new UsuarioPrincipal(5, "Luis Caja", "luis", "CAJERO");
    }

    // ------------------------------------------------------------------
    // PUNTO 10 - el total lo pone el servidor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("El total sale de los precios de la base de datos, no de lo que pida el navegador")
    void elTotalVieneDeLaBaseDeDatos() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Tela Achocolatada", "15.00")));
        when(productoRepo.descontarStock(eq(1), anyInt())).thenReturn(1);

        VentaRequest request = new VentaRequest(
                "Cliente", "72345678", "Boleta con DNI", "EFECTIVO",
                new BigDecimal("30.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 2, List.of()))
        );

        VentaResponse r = servicio.registrarVenta(request, cajero);

        // 2 x S/ 15.00 de la BD. Aunque el navegador hubiera mandado otra cosa,
        // aqui no cabe: VentaRequest no tiene campo de precio.
        assertThat(r.total()).isEqualByComparingTo("30.00");
        assertThat(r.subtotal()).isEqualByComparingTo("25.42");
        assertThat(r.igv()).isEqualByComparingTo("4.58");
        assertThat(r.subtotal().add(r.igv())).isEqualByComparingTo(r.total());
    }

    @ParameterizedTest
    @CsvSource({"EFECTIVO_POS, Efectivo + POS", "EFECTIVO_YAPE, Efectivo + Yape"})
    void pagoCombinadoConservaCanalYDescuentaElVueltoDelEfectivo(String codigo, String nombre) {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Crepe", "23.00")));
        when(productoRepo.descontarStock(eq(1), anyInt())).thenReturn(1);
        VentaRequest request = new VentaRequest("Cliente", "", "BOLETA_SIMPLE", codigo,
                new BigDecimal("20.00"), new BigDecimal("8.00"),
                List.of(new ItemVentaRequest(1, 1, List.of())));

        VentaResponse respuesta = servicio.registrarVenta(request, cajero);

        assertThat(respuesta.metodoPago()).isEqualTo(nombre);
        assertThat(respuesta.montoEfectivo()).isEqualByComparingTo("15.00");
        assertThat(respuesta.montoDigital()).isEqualByComparingTo("8.00");
        assertThat(respuesta.vuelto()).isEqualByComparingTo("5.00");
        verify(ventaRepo).save(org.mockito.ArgumentMatchers.argThat(v -> codigo.equals(v.getMetodoPago())));
    }

    @Test
    void notaDeVentaNoExigeDocumentoNiConsumeElCorrelativoDeBoletas() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Crepe", "14.00")));
        when(productoRepo.descontarStock(eq(1), anyInt())).thenReturn(1);
        when(ventaRepo.siguienteCorrelativoNotaVenta()).thenReturn(8L);
        VentaRequest request = new VentaRequest(null, null, "Nota de venta", "EFECTIVO",
                new BigDecimal("14.00"), BigDecimal.ZERO, List.of(new ItemVentaRequest(1, 1, List.of())));

        VentaResponse respuesta = servicio.registrarVenta(request, cajero);

        assertThat(respuesta.tipoComprobante()).isEqualTo("NOTA_VENTA");
        assertThat(respuesta.numeroComprobante()).isEqualTo("NV01-000008");
        assertThat(respuesta.total()).isEqualByComparingTo("14.00");
        assertThat(respuesta.clienteNombre()).isEqualTo("CLIENTE GENERAL");
        verify(ventaRepo, never()).siguienteCorrelativo();
    }

    @Test
    void consultarYReintentarVentaConservaElDesgloseSinConsultarPreciosActuales() {
        Producto base = producto(1, "Crepe", "14.00");
        Producto extra = producto(9, "Oreo", "2.50");
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(base, extra));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaRequest request = new VentaRequest(null, null, "NOTA_VENTA", "EFECTIVO",
                new BigDecimal("33.00"), BigDecimal.ZERO, List.of(new ItemVentaRequest(1, 2, List.of(9))));
        VentaResponse original = servicio.registrarVenta(request, cajero, "desglose-guardado");

        ArgumentCaptor<Venta> venta = ArgumentCaptor.forClass(Venta.class);
        verify(ventaRepo).save(venta.capture());
        ArgumentCaptor<Iterable<DetalleVenta>> detalles = ArgumentCaptor.forClass(Iterable.class);
        verify(detalleRepo).saveAll(detalles.capture());
        DetalleVenta detalle = detalles.getValue().iterator().next();
        var converter = new com.lastulas.ica.AdicionalesCobradosConverter();
        detalle.setAdicionalesCobrados(converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(detalle.getAdicionalesCobrados())));
        when(ventaRepo.findById(77)).thenReturn(Optional.of(venta.getValue()));
        when(ventaRepo.findByClaveIdempotencia("desglose-guardado")).thenReturn(Optional.of(venta.getValue()));
        when(detalleRepo.findByIdVenta(77)).thenReturn(List.of(detalle));
        base.setPrecio(new BigDecimal("50.00"));
        extra.setPrecio(new BigDecimal("10.00"));

        assertThat(servicio.obtenerVenta(77).detalles()).isEqualTo(original.detalles());
        assertThat(servicio.registrarVenta(request, cajero, "desglose-guardado").detalles()).isEqualTo(original.detalles());
        verify(productoRepo).findByIdIn(anyList());
    }

    @Test
    @DisplayName("El cajero lo decide el token, nunca el cuerpo de la peticion")
    void elCajeroSaleDelToken() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Tela", "10.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);

        VentaRequest request = new VentaRequest(
                "Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("10.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of()))
        );

        VentaResponse r = servicio.registrarVenta(request, cajero);
        assertThat(r.idUsuario()).isEqualTo(5);
        verify(ventaRepo).save(org.mockito.ArgumentMatchers.argThat(v -> Integer.valueOf(9).equals(v.getIdCajaTurno())));
    }

    @Test
    void cajaGeneralCerradaRechazaElCobroAntesDeTocarStock() {
        when(cajaTurno.turnoAbierto(anyInt())).thenReturn(Optional.empty());
        VentaRequest request = new VentaRequest("Cliente", "", "BOLETA_SIMPLE", "EFECTIVO",
                BigDecimal.TEN, BigDecimal.ZERO, List.of(new ItemVentaRequest(1, 1, List.of())));
        assertThatThrownBy(() -> servicio.registrarVenta(request, cajero)).hasMessageContaining("caja general está cerrada");
        verify(productoRepo, never()).descontarStock(anyInt(), anyInt());
        verify(ventaRepo, never()).save(any());
    }

    @Test
    @DisplayName("Se descuenta stock de la base y de cada topping")
    void descuentaStockDeProductosYToppings() {
        when(productoRepo.findByIdIn(anyList()))
                .thenReturn(List.of(producto(1, "Tela", "15.00"), producto(9, "Chips", "2.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);

        VentaRequest request = new VentaRequest(
                "Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("34.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 2, List.of(9)))
        );

        VentaResponse r = servicio.registrarVenta(request, cajero);

        // (15.00 + 2.00) x 2 = 34.00
        assertThat(r.total()).isEqualByComparingTo("34.00");
        // 2 unidades de cada producto, toppings incluidos.
        verify(productoRepo).descontarStock(1, 2);
        verify(productoRepo).descontarStock(9, 2);
    }

    @Test
    @DisplayName("Sin stock la venta se rechaza y no queda nada guardado")
    void sinStockNoSeGuardaNada() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Tela", "15.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(0);

        VentaRequest request = new VentaRequest(
                "Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("15.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of()))
        );

        assertThatThrownBy(() -> servicio.registrarVenta(request, cajero))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No hay stock suficiente");

        verify(ventaRepo, never()).save(any());
    }

    @Test
    @DisplayName("Un pago incompleto no genera venta")
    void pagoIncompletoSeRechaza() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Tela", "15.00")));

        VentaRequest request = new VentaRequest(
                "Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("5.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of()))
        );

        assertThatThrownBy(() -> servicio.registrarVenta(request, cajero))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Faltan");

        verify(ventaRepo, never()).save(any());
    }

    @Test
    @DisplayName("Reintentar la misma clave devuelve la venta original sin volver a descontar stock")
    void repetirClaveDeIdempotenciaNoDuplicaLaVenta() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Tela", "15.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        when(ventaRepo.siguienteCorrelativo()).thenReturn(42L);

        VentaRequest request = new VentaRequest(
                "Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("15.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of()))
        );
        String clave = "5b31e1b4-2ab8-47cd-9c47-969f663bd15f";

        VentaResponse primera = servicio.registrarVenta(request, cajero, clave);
        ArgumentCaptor<Venta> guardada = ArgumentCaptor.forClass(Venta.class);
        verify(ventaRepo).save(guardada.capture());
        when(ventaRepo.findByClaveIdempotencia(clave)).thenReturn(Optional.of(guardada.getValue()));
        when(detalleRepo.findByIdVenta(77)).thenReturn(List.of());

        VentaResponse reintentada = servicio.registrarVenta(request, cajero, clave);

        assertThat(reintentada.idVenta()).isEqualTo(primera.idVenta());
        assertThat(reintentada.numeroComprobante()).isEqualTo(primera.numeroComprobante());
        verify(ventaRepo).save(any(Venta.class));
        verify(productoRepo).descontarStock(1, 1);
    }

    @Test
    @DisplayName("La misma clave no se acepta con otros datos")
    void claveDeIdempotenciaNoSePuedeReutilizarConOtroCuerpo() {
        Venta venta = ventaEmitida();
        venta.setClaveIdempotencia("5b31e1b4-2ab8-47cd-9c47-969f663bd15f");
        venta.setHuellaIdempotencia("huella-distinta");
        when(ventaRepo.findByClaveIdempotencia(venta.getClaveIdempotencia()))
                .thenReturn(Optional.of(venta));

        VentaRequest request = new VentaRequest(
                "Cliente distinto", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("15.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of()))
        );

        assertThatThrownBy(() -> servicio.registrarVenta(
                request, cajero, venta.getClaveIdempotencia()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya se uso")
                .extracting("httpStatus")
                .isEqualTo(409);
        verify(productoRepo, never()).descontarStock(anyInt(), anyInt());
    }

    @Test
    @DisplayName("El vuelto lo calcula el servidor y no se guarda como ingreso")
    void elVueltoNoEntraComoIngreso() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Tela", "15.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);

        // El cajero recibe un billete de S/ 20 para una venta de S/ 15.
        VentaRequest request = new VentaRequest(
                "Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("20.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of()))
        );

        VentaResponse r = servicio.registrarVenta(request, cajero);

        assertThat(r.vuelto()).isEqualByComparingTo("5.00");
        assertThat(r.montoEfectivo()).isEqualByComparingTo("15.00");
        assertThat(r.total()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("El comprobante impreso es el correlativo guardado, no el id de la venta")
    void elNumeroImpresoEsElCorrelativoGuardado() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Tela", "15.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        // La secuencia entrega el 42, aunque id_venta valga 77: si el
        // correlativo se dejara al DEFAULT de la columna, Hibernate no lo
        // releeria y se imprimiria B001-000077, que no existe en la base.
        when(ventaRepo.siguienteCorrelativo()).thenReturn(42L);

        VentaRequest request = new VentaRequest(
                "Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("15.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of()))
        );

        VentaResponse r = servicio.registrarVenta(request, cajero);

        ArgumentCaptor<Venta> guardada = ArgumentCaptor.forClass(Venta.class);
        verify(ventaRepo).save(guardada.capture());
        assertThat(guardada.getValue().getNumeroCorrelativo()).isEqualTo(42);
        assertThat(r.numeroComprobante()).isEqualTo("B001-000042");
        // La fecha tambien se escribe en el INSERT, asi que nunca es null.
        assertThat(r.fechaVenta()).isNotNull();
        assertThat(guardada.getValue().getFechaVenta()).isNotNull();
    }

    // ------------------------------------------------------------------
    // PUNTO 11 - anular no es borrar
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Anular deja la venta en la base marcada ANULADA y emite la nota de credito")
    void anularNoBorraLaVenta() {
        Venta venta = ventaEmitida();
        when(ventaRepo.findById(77)).thenReturn(Optional.of(venta));
        when(notaCreditoRepo.save(any(NotaCredito.class))).thenAnswer(inv -> inv.getArgument(0));

        DetalleVenta det = new DetalleVenta();
        det.setIdVenta(77);
        det.setIdProducto(1);
        det.setCantidad(2);
        det.setToppingsIds("9");
        when(detalleRepo.findByIdVenta(77)).thenReturn(List.of(det));

        var respuesta = servicio.anularVenta(77, "Cliente devolvio el producto", cajero);

        // Lo que NUNCA debe pasar: el registro fiscal se borra.
        // any(Venta.class) y no any() a secas: VentaRepository ahora hereda
        // JpaSpecificationExecutor, que agrega un delete(Specification) y deja
        // any() con dos firmas posibles.
        verify(ventaRepo, never()).delete(any(Venta.class));
        verify(ventaRepo, never()).deleteById(anyInt());

        assertThat(venta.getEstado()).isEqualTo(EstadoVenta.ANULADA);
        assertThat(respuesta.get("numeroNotaCredito")).isEqualTo("BC01-000001");
        assertThat(venta.getMotivoAnulacion()).isEqualTo("Cliente devolvio el producto");
        assertThat(venta.getIdUsuarioAnulacion()).isEqualTo(5);
    }

    @Test
    @DisplayName("Anular devuelve el stock, toppings incluidos")
    void anularDevuelveElStock() {
        Venta venta = ventaEmitida();
        when(ventaRepo.findById(77)).thenReturn(Optional.of(venta));
        when(notaCreditoRepo.save(any(NotaCredito.class))).thenAnswer(inv -> inv.getArgument(0));

        DetalleVenta det = new DetalleVenta();
        det.setIdVenta(77);
        det.setIdProducto(1);
        det.setCantidad(3);
        det.setToppingsIds("9,9,10");
        det.setSaboresIds("11,12");
        det.setFrutasIds("13,13,14");
        when(detalleRepo.findByIdVenta(77)).thenReturn(List.of(det));

        servicio.anularVenta(77, "Error de cobro", cajero);

        verify(productoRepo).devolverStock(1, 3);
        verify(productoRepo).devolverStock(9, 6);
        verify(productoRepo).devolverStock(10, 3);
        verify(productoRepo).devolverStock(11, 3);
        verify(productoRepo).devolverStock(12, 3);
        verify(productoRepo).devolverStock(13, 6);
        verify(productoRepo).devolverStock(14, 3);
    }

    @Test
    void saboresYToppingsGratisSeGuardanSinCambiarElTotal() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(
                producto(1, "Helado", "10.00"), producto(9, "Chocolate", "0.00"),
                producto(11, "Fresa", "0.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaRequest req = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("20.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 2, List.of(9), List.of(11))));
        VentaResponse respuesta = servicio.registrarVenta(req, cajero);
        assertThat(respuesta.total()).isEqualByComparingTo("20.00");
        assertThat(respuesta.detalles().getFirst().sabores()).isEqualTo("Fresa");
        assertThat(respuesta.detalles().getFirst().toppings()).isEqualTo("Chocolate");
        assertThat(respuesta.detalles().getFirst().adicionalesCobrados()).isEmpty();
        assertThat(respuesta.detalles().getFirst().precioBaseUnitario()).isEqualByComparingTo("10.00");
        verify(productoRepo).descontarStock(11, 2);
        verify(productoRepo).descontarStock(9, 2);
        verify(detalleRepo).saveAll(org.mockito.ArgumentMatchers.<Iterable<DetalleVenta>>argThat(
                detalles -> "11".equals(detalles.iterator().next().getSaboresIds())));
    }

    @Test
    void cambiarSaboresNoPermiteReutilizarLaClaveDeCobro() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(
                producto(1, "Helado", "10.00"), producto(11, "Fresa", "0.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaRequest req = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                BigDecimal.TEN, BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of(), List.of(11))));
        servicio.registrarVenta(req, cajero, "clave-sabores");
        ArgumentCaptor<Venta> venta = ArgumentCaptor.forClass(Venta.class);
        verify(ventaRepo).save(venta.capture());
        when(ventaRepo.findByClaveIdempotencia("clave-sabores")).thenReturn(Optional.of(venta.getValue()));
        VentaRequest sinSabor = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                BigDecimal.TEN, BigDecimal.ZERO, List.of(new ItemVentaRequest(1, 1, List.of(), List.of())));
        assertThatThrownBy(() -> servicio.registrarVenta(sinSabor, cajero, "clave-sabores"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("otros datos");
        verify(productoRepo).descontarStock(11, 1);
    }

    @Test
    void notaSeGuardaPorProductoSinModificarElPrecio() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Helado", "10.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaRequest req = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                BigDecimal.TEN, BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of(), List.of(), "  Sin azucar\r\nServir separado  ")));
        VentaResponse respuesta = servicio.registrarVenta(req, cajero);
        assertThat(respuesta.total()).isEqualByComparingTo("10.00");
        assertThat(respuesta.detalles().getFirst().notas()).isEqualTo("Sin azucar\nServir separado");
        verify(detalleRepo).saveAll(org.mockito.ArgumentMatchers.<Iterable<DetalleVenta>>argThat(
                detalles -> "Sin azucar\nServir separado".equals(detalles.iterator().next().getNotas())));
    }

    @Test
    void consultarVentaDevuelveLasNotasYSaboresGuardadosSinReleerElCatalogo() {
        Venta venta = ventaEmitida();
        venta.setMetodoPago("EFECTIVO");
        venta.setNumeroCorrelativo(42);
        DetalleVenta detalle = new DetalleVenta();
        detalle.setIdVenta(77);
        detalle.setIdProducto(1);
        detalle.setNombreProducto("Helado vendido");
        detalle.setCantidad(2);
        detalle.setPrecioUnitario(new BigDecimal("9.00"));
        detalle.setSubtotal(new BigDecimal("18.00"));
        detalle.setToppingsDetalle("Chispas, Chispas");
        detalle.setToppingsIds("9,9");
        detalle.setFrutasDetalle("Banana, Banana");
        detalle.setFrutasIds("13,13");
        detalle.setSaboresDetalle("Fresa");
        detalle.setNotas("Sin azúcar\nServir por separado");
        when(ventaRepo.findById(77)).thenReturn(Optional.of(venta));
        when(detalleRepo.findByIdVenta(77)).thenReturn(List.of(detalle));

        var respuesta = servicio.obtenerVenta(77);
        var linea = respuesta.detalles().getFirst();
        assertThat(linea.nombreProducto()).isEqualTo("Helado vendido");
        assertThat(linea.cantidad()).isEqualTo(2);
        assertThat(linea.toppings()).isEqualTo("x2 Chispas");
        assertThat(linea.frutas()).isEqualTo("x2 Banana");
        assertThat(linea.sabores()).isEqualTo("Fresa");
        assertThat(linea.notas()).isEqualTo("Sin azúcar\nServir por separado");
        assertThat(linea.precioBaseUnitario()).isNull();
        assertThat(linea.adicionalesCobrados()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(productoRepo);
    }

    @Test
    void notaLargaSeRechazaAntesDeDescontarStock() {
        VentaRequest req = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                BigDecimal.TEN, BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, null, null, "x".repeat(301))));
        assertThatThrownBy(() -> servicio.registrarVenta(req, cajero)).hasMessageContaining("300 caracteres");
        verify(productoRepo, never()).descontarStock(anyInt(), anyInt());
        verify(ventaRepo, never()).save(any());
    }

    @Test
    void cambiarNotaConLaMismaClaveDeCobroSeRechaza() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(producto(1, "Helado", "10.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaRequest req = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                BigDecimal.TEN, BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, null, null, "Sin azucar")));
        servicio.registrarVenta(req, cajero, "clave-notas");
        ArgumentCaptor<Venta> venta = ArgumentCaptor.forClass(Venta.class);
        verify(ventaRepo).save(venta.capture());
        when(ventaRepo.findByClaveIdempotencia("clave-notas")).thenReturn(Optional.of(venta.getValue()));
        VentaRequest cambio = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                BigDecimal.TEN, BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, null, null, "Con azucar")));
        assertThatThrownBy(() -> servicio.registrarVenta(cambio, cajero, "clave-notas"))
                .hasMessageContaining("otros datos");
        verify(productoRepo).descontarStock(1, 1);
    }

    @Test
    @DisplayName("Una venta ya anulada no se puede anular dos veces")
    void noSeAnulaDosVeces() {
        Venta venta = ventaEmitida();
        venta.setEstado(EstadoVenta.ANULADA);
        when(ventaRepo.findById(77)).thenReturn(Optional.of(venta));

        assertThatThrownBy(() -> servicio.anularVenta(77, "otra vez", cajero))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya fue anulada");

        verify(notaCreditoRepo, never()).save(any());
    }

    @Test
    @DisplayName("Sin motivo la anulacion se rechaza")
    void anularSinMotivoSeRechaza() {
        when(ventaRepo.findById(77)).thenReturn(Optional.of(ventaEmitida()));

        assertThatThrownBy(() -> servicio.anularVenta(77, "   ", cajero))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("motivo");
    }

    // ------------------------------------------------------------------

    private Venta ventaEmitida() {
        Venta v = new Venta();
        v.setIdVenta(77);
        v.setNumeroSerie("B001");
        v.setIdUsuario(5);
        v.setSubtotal(new BigDecimal("15.25"));
        v.setIgv(new BigDecimal("2.75"));
        v.setTotal(new BigDecimal("18.00"));
        v.setEstado(EstadoVenta.EMITIDA);
        return v;
    }

    private Producto producto(Integer id, String nombre, String precio) {
        Producto p = new Producto();
        p.setIdProducto(id);
        p.setIdCategoria(1);
        p.setNombreProducto(nombre);
        p.setPrecio(new BigDecimal(precio));
        p.setStock(50);
        return p;
    }

    @Test void cuposIndependientesPorUnidadYExtrasAlPrecioDelCatalogo() {
        Producto base = producto(1, "Crepe", "18.00");
        base.setToppingsIncluidos(1); base.setFrutasIncluidas(2);
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(base,
                producto(9, "Chocolate", "2.50"), producto(10, "Granola", "3.00"),
                producto(11, "Fresa", "2.00"), producto(12, "Platano", "2.00"), producto(13, "Mango", "2.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaResponse respuesta = servicio.registrarVenta(new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("46.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 2, List.of(9, 10), null, null, List.of(11, 12, 13)))), cajero);
        assertThat(respuesta.total()).isEqualByComparingTo("46.00");
        assertThat(respuesta.detalles().getFirst().frutas()).isEqualTo("Fresa, Platano, Mango");
        var linea = respuesta.detalles().getFirst();
        assertThat(linea.precioBaseUnitario()).isEqualByComparingTo("18.00");
        assertThat(linea.adicionalesCobrados()).extracting(com.lastulas.ica.AdicionalCobrado::nombreProducto)
                .containsExactly("Granola", "Mango");
        BigDecimal unitarioDesglosado = linea.adicionalesCobrados().stream()
                .map(com.lastulas.ica.AdicionalCobrado::precioUnitario)
                .reduce(linea.precioBaseUnitario(), BigDecimal::add);
        assertThat(unitarioDesglosado.multiply(BigDecimal.valueOf(linea.cantidad())))
                .isEqualByComparingTo(linea.subtotal());
        verify(productoRepo).descontarStock(11, 2);
        verify(productoRepo).descontarStock(12, 2);
        verify(productoRepo).descontarStock(13, 2);
        verify(productoRepo).descontarStock(9, 2);
    }
    @ParameterizedTest
    @CsvSource({"1, 25.00, 3", "2, 50.00, 6"})
    void porcionesRepetidasConsumenCuposYCobranSoloExcedentes(int cantidad, String total, int stock) {
        Producto base = producto(1, "Crepe", "18.00");
        base.setToppingsIncluidos(1);
        base.setFrutasIncluidas(2);
        Producto chocolate = producto(9, "Chocolate", "2.50");
        chocolate.setIdCategoria(2); chocolate.setCategoriasDestino(Set.of(1));
        Producto fresa = producto(11, "Fresa", "2.00");
        fresa.setIdCategoria(3); fresa.setCategoriasDestino(Set.of(1));
        Categoria carta = new Categoria(); carta.setIdCategoria(1); carta.setNombre("CREPES");
        Categoria toppings = new Categoria(); toppings.setIdCategoria(2); toppings.setEsTopping(true);
        Categoria frutas = new Categoria(); frutas.setIdCategoria(3); frutas.setEsFruta(true);
        CategoriaRepository categorias = mock(CategoriaRepository.class);
        when(categorias.findAllByOrderByIdCategoriaAsc()).thenReturn(List.of(carta, toppings, frutas));
        ToppingCatalogoService validadorReal = new ToppingCatalogoService(categorias);
        when(toppingsCatalogo.validarRecetas(any(), anyList())).thenAnswer(invocacion ->
                validadorReal.validarRecetas(invocacion.getArgument(0), invocacion.getArgument(1)));
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(base, chocolate, fresa));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaResponse respuesta = servicio.registrarVenta(new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal(total), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, cantidad, List.of(9, 9, 9), null, null, List.of(11, 11, 11)))), cajero);
        assertThat(respuesta.total()).isEqualByComparingTo(total);
        var linea = respuesta.detalles().getFirst();
        assertThat(linea.precioUnitario()).isEqualByComparingTo("25.00");
        assertThat(linea.toppings()).isEqualTo("x3 Chocolate");
        assertThat(linea.frutas()).isEqualTo("x3 Fresa");
        assertThat(linea.adicionalesCobrados()).extracting(com.lastulas.ica.AdicionalCobrado::nombreProducto)
                .containsExactly("Chocolate", "Chocolate", "Fresa");
        assertThat(linea.adicionalesCobrados().stream().map(com.lastulas.ica.AdicionalCobrado::precioUnitario)
                .reduce(linea.precioBaseUnitario(), BigDecimal::add).multiply(BigDecimal.valueOf(cantidad)))
                .isEqualByComparingTo(linea.subtotal());
        ArgumentCaptor<List<DetalleVenta>> detalles = ArgumentCaptor.forClass(List.class);
        verify(detalleRepo).saveAll(detalles.capture());
        assertThat(detalles.getValue().getFirst().getToppingsIds()).isEqualTo("9,9,9");
        assertThat(detalles.getValue().getFirst().getFrutasIds()).isEqualTo("11,11,11");
        verify(productoRepo).descontarStock(1, cantidad);
        verify(productoRepo).descontarStock(9, stock);
        verify(productoRepo).descontarStock(11, stock);
    }

    @Test void sinCuposCobraCadaFrutaYConCupoNoCobraLasIncluidas() {
        Producto base = producto(1, "Crepe", "18.00");
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(base, producto(11, "Fresa", "2.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaRequest req = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("20.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of(), null, null, List.of(11))));
        assertThat(servicio.registrarVenta(req, cajero).total()).isEqualByComparingTo("20.00");
        base.setFrutasIncluidas(2);
        assertThat(servicio.registrarVenta(req, cajero).total()).isEqualByComparingTo("18.00");
    }

    @Test void preparacionRamenIncluyeTresToppingsPorUnidadYCobraElCuarto() {
        prepararCatalogoRamen("RAMEN");
        VentaResponse respuesta = venderConToppings(List.of(9, 10, 11, 12, 13), 2);
        // (9.50 de ramen + 5 de preparacion + 4 del cuarto topping) x 2.
        assertThat(respuesta.total()).isEqualByComparingTo("37.00");
        assertThat(respuesta.detalles().getFirst().precioUnitario()).isEqualByComparingTo("18.50");
        assertThat(respuesta.detalles().getFirst().toppings()).isEqualTo(
                "Preparación, Huevo, Tocino, Queso, Jamón");
        assertThat(respuesta.detalles().getFirst().adicionalesCobrados())
                .extracting(com.lastulas.ica.AdicionalCobrado::nombreProducto)
                .containsExactly("Preparación", "Jamón");
        for (int id : List.of(1, 9, 10, 11, 12, 13)) {
            verify(productoRepo).descontarStock(id, 2);
        }
    }

    @Test void ramenCuentaPorcionesRepetidasYCobraCadaPreparacionSinAmpliarElCupo() {
        prepararCatalogoRamen("RAMEN");
        assertThat(venderConToppings(List.of(9, 10, 10, 10, 10), 1).total()).isEqualByComparingTo("15.50");
        assertThat(venderConToppings(List.of(9, 9, 10, 10, 10, 10), 1).total()).isEqualByComparingTo("20.50");
    }

    @Test void preparacionRamenSeCobraAunqueSeSeleccioneDespuesDeLosToppings() {
        prepararCatalogoRamen(" ramen ");
        assertThat(venderConToppings(List.of(10, 11, 12, 9), 1).total()).isEqualByComparingTo("14.50");
        assertThat(venderConToppings(List.of(10, 9, 11, 12), 1).total()).isEqualByComparingTo("14.50");
        assertThat(venderConToppings(List.of(9), 1).total()).isEqualByComparingTo("14.50");
    }

    @Test void ramenSinPreparacionCobraTodosLosToppings() {
        prepararCatalogoRamen("RAMEN");
        assertThat(venderConToppings(List.of(10, 11, 12), 1).total()).isEqualByComparingTo("15.50");
        assertThat(venderConToppings(List.of(), 1).total()).isEqualByComparingTo("9.50");
    }

    @Test void ramenSoloIncluyeToppingsConPreparacionAunqueTengaUnCupoConfigurado() {
        prepararCatalogoRamen("RAMEN").setToppingsIncluidos(1);
        assertThat(venderConToppings(List.of(10, 11, 12), 1).total()).isEqualByComparingTo("15.50");
        assertThat(venderConToppings(List.of(9, 10, 11, 12, 13), 1).total()).isEqualByComparingTo("18.50");
    }

    @Test void preparacionNoIncluyeToppingsEnOtrasCategorias() {
        prepararCatalogoRamen("BANDERILLAS");
        assertThat(venderConToppings(List.of(9, 10, 11, 12, 13), 1).total()).isEqualByComparingTo("24.50");
    }

    @Test void toppingsGratisTambienCuentanEntreLosTresIncluidosDelRamen() {
        prepararCatalogoRamen("RAMEN");
        assertThat(venderConToppings(List.of(9, 14, 10, 11, 12), 1).total()).isEqualByComparingTo("17.50");
    }

    @Test void preparacionAdmiteElNombreAnteriorYVariacionesDeAcentosYEspacios() {
        for (String nombre : List.of("Preparación + 3 toppings", " PREPARACION ", "Preparación+3 toppings")) {
            prepararCatalogoRamen("RAMEN", nombre);
            assertThat(venderConToppings(List.of(9, 10, 11, 12), 1).total()).isEqualByComparingTo("14.50");
        }
    }

    @Test void laPreparacionAplicaAUnRamenNuevoSinConfigurarSuId() {
        Producto nuevo = prepararCatalogoRamen("RAMEN");
        nuevo.setIdProducto(999);
        nuevo.setNombreProducto("Ramen nuevo");
        VentaResponse respuesta = servicio.registrarVenta(new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("100.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(999, 1, List.of(9, 10, 11, 12)))), cajero);
        assertThat(respuesta.total()).isEqualByComparingTo("14.50");
        verify(productoRepo).descontarStock(999, 1);
    }

    private Producto prepararCatalogoRamen(String nombreCategoria) {
        return prepararCatalogoRamen(nombreCategoria, "Preparación");
    }

    private Producto prepararCatalogoRamen(String nombreCategoria, String nombrePreparacion) {
        Categoria categoria = new Categoria();
        categoria.setIdCategoria(67);
        categoria.setNombre(nombreCategoria);
        when(toppingsCatalogo.validarRecetas(any(), anyList())).thenReturn(Map.of(67, categoria));
        Producto base = producto(1, "Ramen", "9.50");
        base.setIdCategoria(67);
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(base,
                producto(9, nombrePreparacion, "5.00"),
                producto(10, "Huevo", "1.00"), producto(11, "Tocino", "2.00"),
                producto(12, "Queso", "3.00"), producto(13, "Jamón", "4.00"),
                producto(14, "Alga Nori", "0.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        return base;
    }

    private VentaResponse venderConToppings(List<Integer> toppings, int cantidad) {
        return servicio.registrarVenta(new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("100.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, cantidad, toppings))), cajero);
    }

    @Test void cambiarFrutasNoPermiteReutilizarLaClaveDeCobro() {
        when(productoRepo.findByIdIn(anyList())).thenReturn(List.of(
                producto(1, "Crepe", "10.00"), producto(11, "Fresa", "2.00")));
        when(productoRepo.descontarStock(anyInt(), anyInt())).thenReturn(1);
        VentaRequest req = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("12.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of(), null, null, List.of(11))));
        servicio.registrarVenta(req, cajero, "clave-frutas");
        ArgumentCaptor<Venta> venta = ArgumentCaptor.forClass(Venta.class);
        verify(ventaRepo).save(venta.capture());
        when(ventaRepo.findByClaveIdempotencia("clave-frutas")).thenReturn(Optional.of(venta.getValue()));
        VentaRequest sinFruta = new VentaRequest("Cliente", "", "Boleta simple", "EFECTIVO",
                new BigDecimal("12.00"), BigDecimal.ZERO,
                List.of(new ItemVentaRequest(1, 1, List.of(), null, null, List.of())));
        assertThatThrownBy(() -> servicio.registrarVenta(sinFruta, cajero, "clave-frutas"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("otros datos");
    }
}
