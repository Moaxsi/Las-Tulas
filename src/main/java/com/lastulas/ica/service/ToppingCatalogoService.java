package com.lastulas.ica.service;

import com.lastulas.ica.Categoria;
import com.lastulas.ica.CategoriaRepository;
import com.lastulas.ica.Producto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Valida adicionales contra las categorias configuradas en inventario. */
@Service
public class ToppingCatalogoService {
    private final CategoriaRepository categoriaRepo;

    public ToppingCatalogoService(CategoriaRepository categoriaRepo) {
        this.categoriaRepo = categoriaRepo;
    }

    public record LineaVenta(Integer idProducto, List<Integer> toppings, List<Integer> sabores, List<Integer> frutas) {
        public LineaVenta(Integer idProducto, List<Integer> toppings, List<Integer> sabores) {
            this(idProducto, toppings, sabores, null);
        }
        public LineaVenta(Integer idProducto, List<Integer> toppings) {
            this(idProducto, toppings, null, null);
        }
    }

    @Transactional(readOnly = true)
    public Map<Integer, Categoria> validarRecetas(Map<Integer, Producto> catalogo, List<LineaVenta> lineas) {
        if (lineas == null || lineas.isEmpty()) return Map.of();
        Map<Integer, Categoria> categorias = categoriaRepo.findAllByOrderByIdCategoriaAsc().stream()
                .collect(Collectors.toMap(Categoria::getIdCategoria, c -> c));
        for (LineaVenta linea : lineas) {
            Producto base = catalogo.get(linea.idProducto());
            if (base == null) throw new BusinessException("El producto no existe en el catalogo.");
            Categoria categoria = categorias.get(base.getIdCategoria());
            if (categoria == null || categoria.esCategoriaTopping() || categoria.esCategoriaSabor() || categoria.esCategoriaFruta()) {
                throw new BusinessException("Un topping, sabor o fruta no se puede vender como producto base.");
            }
            validarAdicionales(catalogo, categorias, base, linea.toppings(), "topping");
            validarAdicionales(catalogo, categorias, base, linea.sabores(), "sabor");
            validarAdicionales(catalogo, categorias, base, linea.frutas(), "fruta");
        }
        return categorias;
    }

    private void validarAdicionales(Map<Integer, Producto> catalogo, Map<Integer, Categoria> categorias,
                                   Producto base, List<Integer> ids, String tipo) {
        if (ids == null) return;
        if (ids.size() > 25) throw new BusinessException("Selecciona como maximo 25 adicionales de cada tipo.");
        Set<Integer> vistos = new HashSet<>();
        for (Integer id : ids) {
            if (id == null) throw new BusinessException("Hay adicionales vacios.");
            // Los ids repetidos representan porciones; los sabores siguen siendo unicos.
            if (tipo.equals("sabor") && !vistos.add(id)) {
                throw new BusinessException("Hay sabores repetidos.");
            }
            Producto adicional = catalogo.get(id);
            boolean sabor = tipo.equals("sabor");
            if (adicional == null) throw new BusinessException("El " + tipo + " no existe en el catalogo.");
            Categoria categoria = categorias.get(adicional.getIdCategoria());
            if (categoria == null || !(sabor ? categoria.esCategoriaSabor() : tipo.equals("fruta") ? categoria.esCategoriaFruta() : categoria.esCategoriaTopping())) {
                throw new BusinessException('"' + adicional.getNombreProducto() + "\" no es un " + tipo + ".");
            }
            if (!adicional.getCategoriasDestino().contains(base.getIdCategoria())) {
                throw new BusinessException('"' + adicional.getNombreProducto() + "\" no es un " + tipo
                        + " de la categoria de \"" + base.getNombreProducto() + "\".");
            }
            if (sabor && (adicional.getPrecio() == null || adicional.getPrecio().compareTo(BigDecimal.ZERO) != 0)) {
                throw new BusinessException("Los sabores deben ser gratuitos.");
            }
        }
    }
}
