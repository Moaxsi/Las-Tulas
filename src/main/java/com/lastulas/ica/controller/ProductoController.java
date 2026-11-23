package com.lastulas.ica.controller;

import com.lastulas.ica.Auditoria;
import com.lastulas.ica.Categoria;
import com.lastulas.ica.CategoriaRepository;
import com.lastulas.ica.Producto;
import com.lastulas.ica.ProductoEstado;
import com.lastulas.ica.ProductoRepository;
import com.lastulas.ica.ProductoTopping;
import com.lastulas.ica.security.UsuarioPrincipal;
import com.lastulas.ica.service.AuditoriaService;
import com.lastulas.ica.service.BusinessException;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Puntos 6, 8 y 9.
 *
 * Punto 9: cada operacion declara quien puede hacerla. El POS del cajero solo
 * LEE productos; escribir el catalogo es del admin.
 *
 * Punto 8: la validacion de la referencia repetida vive ACA y no solo en el
 * HTML. El UNIQUE de la base lanzaba una excepcion que el frontend no leia,
 * asi que el usuario veia un error 500 en vez de "esa referencia ya existe".
 *
 * Toppings y sabores se configuran con categoriasDestino al guardar el producto.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")
public class ProductoController {

    private final ProductoRepository productoRepo;
    private final CategoriaRepository categoriaRepo;
    private final AuditoriaService auditoria;

    public ProductoController(ProductoRepository productoRepo,
                               CategoriaRepository categoriaRepo,
                               AuditoriaService auditoria) {
        this.productoRepo = productoRepo;
        this.categoriaRepo = categoriaRepo;
        this.auditoria = auditoria;
    }

    /**
     * Carta del POS. Solo productos activos: un producto dado de baja no puede
     * seguir apareciendo en la pantalla de venta (punto 9).
     *
     * ?incluirInactivos=true es para el admin, que necesita ver la lista
     * completa para dar de baja o reactivar.
     */
    @GetMapping("/productos")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public List<Producto> getProductos(@RequestParam(required = false) Integer categoria,
                                       @RequestParam(defaultValue = "false") boolean incluirInactivos) {
        if (categoria != null && categoria > 0) {
            return incluirInactivos
                    ? productoRepo.findByIdCategoriaOrderByIdProductoDesc(categoria)
                    : productoRepo.findByActivoTrueAndIdCategoriaOrderByIdProductoDesc(categoria);
        }
        return incluirInactivos
                ? productoRepo.findAllByOrderByIdProductoDesc()
                : productoRepo.findByActivoTrueOrderByIdProductoDesc();
    }

    @GetMapping("/categorias")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public List<Categoria> getCategorias() {
        return categoriaRepo.findAllByOrderByIdCategoriaAsc();
    }

    public record Cupos(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0)
                        @jakarta.validation.constraints.Max(25) Integer toppingsIncluidos,
                        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0)
                        @jakarta.validation.constraints.Max(25) Integer frutasIncluidas) {}

    @PutMapping("/productos/{id}/incluidos")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> guardarIncluidos(@PathVariable Integer id,
            @jakarta.validation.Valid @RequestBody Cupos cupos,
            @AuthenticationPrincipal UsuarioPrincipal principal) {
        Producto p = productoRepo.findById(id).orElseThrow(() -> BusinessException.noEncontrado("El producto"));
        Categoria c = categoriaRepo.findAllByOrderByIdCategoriaAsc().stream()
                .filter(cat -> cat.getIdCategoria().equals(p.getIdCategoria())).findFirst().orElseThrow();
        if (!p.estaActivo() || c.esCategoriaTopping() || c.esCategoriaSabor() || c.esCategoriaFruta())
            throw new BusinessException("Selecciona un producto activo de la carta.");
        String anterior = p.getToppingsIncluidos() + "/" + p.getFrutasIncluidas();
        p.setToppingsIncluidos(cupos.toppingsIncluidos());
        p.setFrutasIncluidas(cupos.frutasIncluidas());
        productoRepo.save(p);
        auditoria.registrar(Auditoria.TABLA_PRODUCTOS, id, "MODIFICAR", anterior,
                p.getToppingsIncluidos() + "/" + p.getFrutasIncluidas(), "Complementos incluidos por unidad", principal);
        return ResponseEntity.ok(Map.of("success", true, "producto", p));
    }

    public record NuevaCategoria(String nombre, String descripcion) {}

    @PostMapping("/categorias")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> crearCategoria(@RequestBody NuevaCategoria datos,
                                            @AuthenticationPrincipal UsuarioPrincipal principal) {
        String nombre = datos.nombre() == null ? "" : datos.nombre().strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (nombre.isBlank() || nombre.length() > 50) {
            throw new BusinessException("Escribe un nombre de categoría de 1 a 50 caracteres.");
        }
        String descripcion = datos.descripcion() == null ? "" : datos.descripcion().strip();
        if (descripcion.length() > 500) {
            throw new BusinessException("La descripción admite hasta 500 caracteres.");
        }
        if (categoriaRepo.contarPorNombre(nombre) > 0) {
            throw new BusinessException("Ya existe una categoría con ese nombre.", 409);
        }
        Categoria categoria = new Categoria();
        categoria.setNombre(nombre);
        categoria.setDescripcion(descripcion);
        try {
            categoria = categoriaRepo.saveAndFlush(categoria);
        } catch (DataIntegrityViolationException ex) {
            // El UNIQUE también protege dos altas simultáneas del mismo nombre.
            throw new BusinessException("No se pudo crear la categoría. Comprueba que el nombre no esté repetido.", 409);
        }
        auditoria.registrar(Auditoria.TABLA_CATEGORIAS, categoria.getIdCategoria(), "CREAR",
                null, nombre, "Alta de categoría", principal);
        return ResponseEntity.status(201).body(Map.of("success", true, "categoria", categoria));
    }

    /**
     * Punto 6: las categorias marcadas es_topping. El POS las usaba hardcodeadas
     * a la 5 (TOPPIN) e ignoraba la 7 (TOPPING), que existe en la base con
     * productos cargados.
     */
    @GetMapping("/categorias/toppings")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public List<Categoria> getCategoriasTopping() {
        return categoriaRepo.findByEsToppingTrueOrderByIdCategoriaAsc();
    }

    @PostMapping("/productos")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> crearProducto(@RequestBody Producto prod,
                                           @AuthenticationPrincipal UsuarioPrincipal principal) {
        prod.setToppingsIncluidos(0);
        prod.setFrutasIncluidas(0);
        validarConfiguracion(prod);
        if (prod.getPrecio() == null || prod.getPrecio().compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "El precio debe ser mayor o igual a cero."));
        }
        if (prod.getReferencia() == null || prod.getReferencia().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "La referencia no puede quedar vacia."));
        }
        prod.setReferencia(prod.getReferencia().trim());

        // Punto 8: se comprueba antes de insertar para devolver un 400 con el
        // mensaje util, en vez de dejar que reviente el UNIQUE de PostgreSQL.
        if (productoRepo.contarPorReferencia(prod.getReferencia()) > 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false,
                            "message", "Ya existe un producto con la referencia \"" + prod.getReferencia() + "\"."));
        }

        if (prod.getStock() == null) prod.setStock(0);
        if (prod.getUrlImagen() == null || prod.getUrlImagen().trim().isEmpty()) {
            prod.setUrlImagen("lastulas.jpg");
        }
        prod.setEstado(estadoSegunStock(prod.getStock()));
        prod.setActivo(true);

        Producto guardado = productoRepo.save(prod);
        auditoria.registrar(Auditoria.TABLA_PRODUCTOS, guardado.getIdProducto(), "CREAR",
                null,
                guardado.getReferencia() + " - " + guardado.getNombreProducto()
                        + " (S/ " + guardado.getPrecio() + ")",
                "Alta de producto", principal);

        return ResponseEntity.ok(Map.of("success", true, "producto", guardado));
    }

    @PutMapping("/productos/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> actualizarProducto(@PathVariable Integer id,
                                                @RequestBody Producto datos,
                                                @AuthenticationPrincipal UsuarioPrincipal principal) {
        validarConfiguracion(datos);
        return productoRepo.findById(id).map(prod -> {
            // Punto 8: la referencia puede cambiar, pero no a otra ya usada.
            String nuevaReferencia = datos.getReferencia() != null && !datos.getReferencia().isBlank()
                    ? datos.getReferencia().trim() : prod.getReferencia();
            if (!nuevaReferencia.equalsIgnoreCase(prod.getReferencia())
                    && productoRepo.contarPorReferenciaDistintaDe(nuevaReferencia, id) > 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false,
                                "message", "Ya existe otro producto con la referencia \"" + nuevaReferencia + "\"."));
            }

            // Punto 12: se guarda el estado anterior de los campos que cambian.
            // Sin esto, un ajuste de precio deja rastro de que algo se toco,
            // pero no de cuanto era antes.
            Map<String, String> anterior = new LinkedHashMap<>();
            anterior.put("referencia", prod.getReferencia());
            anterior.put("nombre", prod.getNombreProducto());
            anterior.put("precio", String.valueOf(prod.getPrecio()));
            anterior.put("stock", String.valueOf(prod.getStock()));
            anterior.put("categoria", String.valueOf(prod.getIdCategoria()));
            anterior.put("categoriasDestino", prod.getCategoriasDestino().toString());

            prod.setNombreProducto(datos.getNombreProducto());
            prod.setReferencia(nuevaReferencia);
            if (datos.getPrecio() == null || datos.getPrecio().compareTo(BigDecimal.ZERO) < 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "El precio debe ser mayor o igual a cero."));
            }
            prod.setPrecio(datos.getPrecio());
            prod.setStock(datos.getStock() != null ? datos.getStock() : 0);
            prod.setIdCategoria(datos.getIdCategoria());
            prod.setCategoriasDestino(datos.getCategoriasDestino());
            prod.setUrlImagen(datos.getUrlImagen() != null && !datos.getUrlImagen().trim().isEmpty()
                    ? datos.getUrlImagen() : "lastulas.jpg");
            prod.setEstado(estadoSegunStock(prod.getStock()));

            Producto guardado = productoRepo.save(prod);

            Map<String, String> nuevo = new LinkedHashMap<>();
            nuevo.put("referencia", guardado.getReferencia());
            nuevo.put("nombre", guardado.getNombreProducto());
            nuevo.put("precio", String.valueOf(guardado.getPrecio()));
            nuevo.put("stock", String.valueOf(guardado.getStock()));
            nuevo.put("categoria", String.valueOf(guardado.getIdCategoria()));
            nuevo.put("categoriasDestino", guardado.getCategoriasDestino().toString());

            if (!anterior.equals(nuevo)) {
                auditoria.registrar(Auditoria.TABLA_PRODUCTOS, id, "MODIFICAR",
                        anterior.toString(), nuevo.toString(),
                        principal.getNombreCompleto() + " edito el producto", principal);
            }
            return ResponseEntity.ok(Map.of("success", true, "producto", guardado));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Punto 9: baja LOGICA, no borrado.
     *
     * Antes hacia productoRepo.deleteById(id). Eso se llevaba por delante las
     * filas de producto_toppings y dejaba el historial de ventas apuntando a
     * un id_producto que ya no existe. Ahora el producto queda con activo=false
     * y sale de la carta y del POS, pero el historial sigue siendo legible.
     *
     * Se conserva el verbo DELETE por compatibilidad con el JS existente, que
     * es un cambio de url minimo para el frontend.
     */
    @DeleteMapping("/productos/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> eliminarProducto(@PathVariable Integer id,
                                              @AuthenticationPrincipal UsuarioPrincipal principal) {
        return productoRepo.findById(id).map(prod -> {
            if (!prod.estaActivo()) {
                return ResponseEntity.ok(Map.of("success", true,
                        "message", "El producto ya estaba dado de baja."));
            }
            prod.setActivo(false);
            productoRepo.save(prod);
            auditoria.registrar(Auditoria.TABLA_PRODUCTOS, id, "DAR_DE_BAJA",
                    "activo=true, stock=" + prod.getStock(),
                    "activo=false",
                    principal.getNombreCompleto() + " dio de baja \""
                            + prod.getNombreProducto() + "\"", principal);
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "Producto dado de baja. Sigue en el historial de ventas."));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Reactivar un producto dado de baja (punto 9). */
    @PostMapping("/productos/{id}/reactivar")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> reactivar(@PathVariable Integer id,
                                       @AuthenticationPrincipal UsuarioPrincipal principal) {
        return productoRepo.findById(id).map(prod -> {
            if (prod.estaActivo()) {
                return ResponseEntity.ok(Map.of("success", true,
                        "message", "El producto ya estaba activo."));
            }
            prod.setActivo(true);
            productoRepo.save(prod);
            auditoria.registrar(Auditoria.TABLA_PRODUCTOS, id, "REACTIVAR",
                    "activo=false", "activo=true",
                    principal.getNombreCompleto() + " reactivo \""
                            + prod.getNombreProducto() + "\"", principal);
            return ResponseEntity.ok(Map.of("success", true, "producto", prod));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    // =================================================================
    // Punto 6: recetas de toppings
    // =================================================================

    /**
     * Recetas de varios productos en UNA peticion.
     *
     * El POS la pide al cargar la carta completa. Si se pidiera una por
     * producto serian 165 llamadas a la API antes de vender nada, cada una con
     * su latencia; con una sola el POS abre como antes.
     *
     * @param ids separados por coma, tal como los manda el query string
     * @return { idProducto: [idTopping, ...] } segun la categoria de cada producto
     */
    @GetMapping("/productos-toppings/receta")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public Map<Integer, List<Integer>> recetas(@RequestParam String ids) {
        List<Integer> lista = new ArrayList<>();
        for (String parte : ids.split(",")) {
            Integer id = toEntero(parte);
            if (id != null && !lista.contains(id)) {
                lista.add(id);
            }
        }
        if (lista.isEmpty()) {
            return Map.of();
        }

        Map<Integer, List<Integer>> recetas = new LinkedHashMap<>();
        List<Producto> adicionales = productoRepo.findByActivoTrueOrderByIdProductoDesc();
        Set<Integer> categoriasTopping = categoriaRepo.findByEsToppingTrueOrderByIdCategoriaAsc().stream()
                .map(Categoria::getIdCategoria).collect(java.util.stream.Collectors.toSet());
        for (Producto base : productoRepo.findByIdIn(lista)) {
            recetas.put(base.getIdProducto(), adicionales.stream()
                    .filter(p -> categoriasTopping.contains(p.getIdCategoria()))
                    .filter(p -> p.getCategoriasDestino().contains(base.getIdCategoria()))
                    .map(Producto::getIdProducto).toList());
        }
        return recetas;
    }

    @GetMapping("/productos/{id}/toppings")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public List<ProductoTopping> toppingsDe(@PathVariable Integer id) {
        return recetas(id.toString()).getOrDefault(id, List.of()).stream()
                .map(topping -> new ProductoTopping(id, topping)).toList();
    }

    // La antigua escritura por producto no debe crear recetas que caja ignore.
    @PutMapping("/productos/{id}/toppings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> guardarToppings(@PathVariable Integer id) {
        return ResponseEntity.status(409).body(Map.of("success", false,
                "message", "Edita el topping en Inventario y selecciona sus categorias de destino."));
    }

    private void validarConfiguracion(Producto prod) {
        if (prod.getNombreProducto() == null || prod.getNombreProducto().isBlank()
                || prod.getNombreProducto().length() > 150) {
            throw new BusinessException("El nombre es obligatorio y admite hasta 150 caracteres.");
        }
        if (prod.getReferencia() == null || prod.getReferencia().isBlank()
                || prod.getReferencia().trim().length() > 30) {
            throw new BusinessException("La referencia es obligatoria y admite hasta 30 caracteres.");
        }
        if (prod.getStock() == null || prod.getStock() < 0) {
            throw new BusinessException("El stock debe ser un entero mayor o igual a cero.");
        }
        List<Categoria> categorias = categoriaRepo.findAllByOrderByIdCategoriaAsc();
        Categoria categoria = categorias.stream().filter(c -> c.getIdCategoria().equals(prod.getIdCategoria()))
                .findFirst().orElseThrow(() -> new BusinessException("Selecciona una categoria valida."));
        if (categoria.esCategoriaSabor()) prod.setPrecio(BigDecimal.ZERO);
        if (prod.getPrecio() == null || prod.getPrecio().signum() < 0 || prod.getPrecio().scale() > 2
                || prod.getPrecio().compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new BusinessException("El precio debe ser mayor o igual a cero y tener hasta dos decimales.");
        }
        if (categoria.esCategoriaTopping() || categoria.esCategoriaSabor() || categoria.esCategoriaFruta()) {
            Set<Integer> destinosValidos = categorias.stream()
                    .filter(c -> !c.esCategoriaTopping() && !c.esCategoriaSabor() && !c.esCategoriaFruta())
                    .map(Categoria::getIdCategoria).collect(java.util.stream.Collectors.toSet());
            if (prod.getCategoriasDestino().isEmpty() || !destinosValidos.containsAll(prod.getCategoriasDestino())) {
                throw new BusinessException("Selecciona una o varias categorias de la carta para este adicional.");
            }
        } else if (!prod.getCategoriasDestino().isEmpty()) {
            throw new BusinessException("Solo los toppings, sabores y frutas pueden tener categorias de destino.");
        }
    }

    private static Integer toEntero(Object valor) {
        if (valor == null) return null;
        if (valor instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(valor.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String estadoSegunStock(Integer stock) {
        return ProductoEstado.estadoSegunStock(stock);
    }
}
