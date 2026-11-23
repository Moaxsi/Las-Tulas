package com.lastulas.ica.service;

import com.lastulas.ica.Categoria;
import com.lastulas.ica.CategoriaRepository;
import com.lastulas.ica.Producto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToppingCatalogoServiceTest {
    private ToppingCatalogoService servicio;
    private Producto base;
    private Producto topping;
    private Producto sabor;
    private Map<Integer, Producto> catalogo;

    @BeforeEach void setUp() {
        CategoriaRepository repo = mock(CategoriaRepository.class);
        when(repo.findAllByOrderByIdCategoriaAsc()).thenReturn(List.of(
                categoria(1, false, false), categoria(2, false, false),
                categoria(5, true, false), categoria(10, false, true)));
        servicio = new ToppingCatalogoService(repo);
        base = producto(1, 1, "10");
        topping = producto(9, 5, "0");
        sabor = producto(10, 10, "0");
        catalogo = Map.of(1, base, 9, topping, 10, sabor);
    }

    @Test void aceptaToppingGratisYSaborDeSuCategoria() {
        assertThatCode(() -> validar(List.of(9), List.of(10))).doesNotThrowAnyException();
    }
    @Test void aceptaToppingDePago() {
        topping.setPrecio(new BigDecimal("2.50"));
        assertThatCode(() -> validar(List.of(9), List.of())).doesNotThrowAnyException();
    }
    @Test void admiteVariasCategorias() {
        topping.setCategoriasDestino(Set.of(1, 2));
        base.setIdCategoria(2);
        assertThatCode(() -> validar(List.of(9), null)).doesNotThrowAnyException();
    }
    @Test void sinAsignacionNoHayFallbackATodos() {
        topping.setCategoriasDestino(Set.of());
        assertThatThrownBy(() -> validar(List.of(9), null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("categoria");
    }
    @Test void rechazaOtraCategoria() {
        base.setIdCategoria(2);
        assertThatThrownBy(() -> validar(null, List.of(10))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("categoria");
    }
    @Test void noConfundeSaboresConToppings() {
        assertThatThrownBy(() -> validar(List.of(10), null)).hasMessageContaining("no es un topping");
        assertThatThrownBy(() -> validar(null, List.of(9))).hasMessageContaining("no es un sabor");
    }
    @Test void saborNuncaTieneCosto() {
        sabor.setPrecio(BigDecimal.ONE);
        assertThatThrownBy(() -> validar(null, List.of(10))).hasMessageContaining("gratuitos");
    }
    @Test void aceptaPorcionesRepetidasDeToppings() {
        assertThatCode(() -> validar(List.of(9, 9), null)).doesNotThrowAnyException();
    }
    @Test void rechazaSaboresDuplicadosEInexistentes() {
        assertThatThrownBy(() -> validar(null, List.of(10, 10))).hasMessageContaining("repetidos");
        assertThatThrownBy(() -> validar(null, List.of(99))).hasMessageContaining("no existe");
    }
    @Test void lasPorcionesRepetidasRespetanElLimiteDe25() {
        assertThatCode(() -> validar(java.util.Collections.nCopies(25, 9), null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validar(java.util.Collections.nCopies(26, 9), null)).hasMessageContaining("25");
    }
    @Test void adicionalNoSeVendeComoBase() {
        assertThatThrownBy(() -> servicio.validarRecetas(catalogo, List.of(
                new ToppingCatalogoService.LineaVenta(9, null, null))))
                .hasMessageContaining("producto base");
    }
    @Test void productoSinAdicionalesSigueVendiendo() {
        assertThatCode(() -> validar(null, null)).doesNotThrowAnyException();
    }
    private void validar(List<Integer> tops, List<Integer> sabores) {
        servicio.validarRecetas(catalogo, List.of(new ToppingCatalogoService.LineaVenta(1, tops, sabores)));
    }
    private static Producto producto(int id, int categoria, String precio) {
        Producto p = new Producto(); p.setIdProducto(id); p.setIdCategoria(categoria);
        p.setNombreProducto("Producto " + id); p.setPrecio(new BigDecimal(precio));
        p.setCategoriasDestino(Set.of(1)); return p;
    }
    private static Categoria categoria(int id, boolean topping, boolean sabor) {
        Categoria c = new Categoria(); c.setIdCategoria(id); c.setEsTopping(topping); c.setEsSabor(sabor); return c;
    }

    @Test void frutaSoloEnSuTipoYDestinoConPrecioLibre() {
        CategoriaRepository repo = mock(CategoriaRepository.class);
        Categoria fruta = categoria(12, false, false); fruta.setEsFruta(true);
        when(repo.findAllByOrderByIdCategoriaAsc()).thenReturn(List.of(categoria(1, false, false), fruta));
        ToppingCatalogoService servicioFrutas = new ToppingCatalogoService(repo);
        Producto fresa = producto(12, 12, "2.00");
        Map<Integer, Producto> datos = Map.of(1, base, 12, fresa);
        assertThatCode(() -> servicioFrutas.validarRecetas(datos, List.of(
                new ToppingCatalogoService.LineaVenta(1, null, null, List.of(12))))) .doesNotThrowAnyException();
        assertThatThrownBy(() -> servicioFrutas.validarRecetas(datos, List.of(
                new ToppingCatalogoService.LineaVenta(1, List.of(12), null, null)))).hasMessageContaining("no es un topping");
        assertThatCode(() -> servicioFrutas.validarRecetas(datos, List.of(
                new ToppingCatalogoService.LineaVenta(1, null, null, List.of(12, 12))))).doesNotThrowAnyException();
        assertThatThrownBy(() -> servicioFrutas.validarRecetas(datos, List.of(
                new ToppingCatalogoService.LineaVenta(1, null, null, java.util.Collections.nCopies(26, 12)))))
                .hasMessageContaining("25");
        fresa.setCategoriasDestino(Set.of());
        assertThatThrownBy(() -> servicioFrutas.validarRecetas(datos, List.of(
                new ToppingCatalogoService.LineaVenta(1, null, null, List.of(12))))).hasMessageContaining("categoria");
    }
}
