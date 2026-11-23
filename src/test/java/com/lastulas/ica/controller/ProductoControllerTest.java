package com.lastulas.ica.controller;

import com.lastulas.ica.*;
import com.lastulas.ica.security.UsuarioPrincipal;
import com.lastulas.ica.service.AuditoriaService;
import com.lastulas.ica.service.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductoControllerTest {
    private ProductoRepository repo;
    private CategoriaRepository categorias;
    private ProductoController controller;
    private final UsuarioPrincipal admin = new UsuarioPrincipal(1, "Admin", "admin", "ADMIN");

    @BeforeEach void setUp() {
        repo = mock(ProductoRepository.class);
        categorias = mock(CategoriaRepository.class);
        when(categorias.findAllByOrderByIdCategoriaAsc()).thenReturn(List.of(
                categoria(1, false, false), categoria(2, false, false),
                categoria(5, true, false), categoria(10, false, true)));
        when(repo.save(any(Producto.class))).thenAnswer(inv -> {
            Producto p = inv.getArgument(0); p.setIdProducto(20); return p;
        });
        controller = new ProductoController(repo, categorias, mock(AuditoriaService.class));
    }

    @Test void creaCategoriaNormalConNombreNormalizado() {
        when(categorias.saveAndFlush(any(Categoria.class))).thenAnswer(inv -> {
            Categoria categoria = inv.getArgument(0); categoria.setIdCategoria(30); return categoria;
        });
        var respuesta = controller.crearCategoria(new ProductoController.NuevaCategoria("  Nuevos   postres  ", "  Fríos  "), admin);
        assertThat(respuesta.getStatusCode().value()).isEqualTo(201);
        verify(categorias).saveAndFlush(argThat(c -> c.getNombre().equals("NUEVOS POSTRES")
                && c.getDescripcion().equals("Fríos") && !c.esCategoriaTopping() && !c.esCategoriaSabor()));
    }

    @Test void rechazaCategoriaRepetidaSinGuardar() {
        when(categorias.contarPorNombre("HELADOS")).thenReturn(1L);
        assertThatThrownBy(() -> controller.crearCategoria(new ProductoController.NuevaCategoria(" helados ", null), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Ya existe");
        verify(categorias, never()).saveAndFlush(any());
    }

    @Test void rechazaCategoriaVaciaOLarga() {
        for (String nombre : new String[] {null, "   ", "x".repeat(51)}) {
            assertThatThrownBy(() -> controller.crearCategoria(new ProductoController.NuevaCategoria(nombre, null), admin))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("50 caracteres");
        }
        assertThatThrownBy(() -> controller.crearCategoria(new ProductoController.NuevaCategoria("Postres", "x".repeat(501)), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("500 caracteres");
        verify(categorias, never()).saveAndFlush(any());
    }

    @Test void devuelveConflictoSiOtroAdministradorCreaElMismoNombre() {
        when(categorias.saveAndFlush(any(Categoria.class))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> controller.crearCategoria(new ProductoController.NuevaCategoria("Postres", null), admin))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getHttpStatus()).isEqualTo(409));
    }

    @Test void creaSaborGratisConVariasCategorias() {
        Producto p = producto(10); p.setPrecio(BigDecimal.TEN);
        assertThat(controller.crearProducto(p, admin).getStatusCode().value()).isEqualTo(200);
        assertThat(p.getPrecio()).isEqualByComparingTo("0");
        assertThat(p.getCategoriasDestino()).containsExactlyInAnyOrder(1, 2);
    }
    @Test void toppingPuedeSerGratis() {
        Producto p = producto(5); p.setPrecio(BigDecimal.ZERO);
        assertThat(controller.crearProducto(p, admin).getStatusCode().value()).isEqualTo(200);
    }
    @Test void rechazaAdicionalSinCategoriaODestinoAdicional() {
        Producto p = producto(5); p.setCategoriasDestino(Set.of());
        assertThatThrownBy(() -> controller.crearProducto(p, admin)).isInstanceOf(BusinessException.class);
        p.setCategoriasDestino(Set.of(10));
        assertThatThrownBy(() -> controller.crearProducto(p, admin)).isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
    }
    @Test void validaAntesDeModificarUnProducto() {
        Producto p = producto(5); p.setStock(-1);
        assertThatThrownBy(() -> controller.actualizarProducto(20, p, admin)).hasMessageContaining("stock");
        verify(repo, never()).findById(any());
        verify(repo, never()).save(any());
    }
    private Producto producto(int categoria) {
        Producto p = new Producto(); p.setNombreProducto("Fresa"); p.setReferencia("FRESA-1");
        p.setIdCategoria(categoria); p.setPrecio(BigDecimal.ONE); p.setStock(20);
        p.setCategoriasDestino(Set.of(1, 2)); return p;
    }
    private Categoria categoria(int id, boolean topping, boolean sabor) {
        Categoria c = new Categoria(); c.setIdCategoria(id); c.setEsTopping(topping); c.setEsSabor(sabor); return c;
    }
}
