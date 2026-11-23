package com.lastulas.ica;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Lecturas de la tabla producto_toppings (punto 5).
 *
 * findPorProductosIn trae TODAS las recetas de los productos de una venta en
 * una sola consulta: pedir una por linea seria el patron N+1 otra vez.
 */
public interface ProductoToppingRepository extends JpaRepository<ProductoTopping, ProductoTopping.ProductoToppingId> {

    @Query("SELECT pt FROM ProductoTopping pt WHERE pt.idProducto IN :ids")
    List<ProductoTopping> findPorProductosIn(@Param("ids") List<Integer> ids);

    /** ids de topping de un producto, para pintar el selector del admin. */
    List<ProductoTopping> findByIdProductoOrderByIdToppingAsc(Integer idProducto);

    /**
     * Reemplaza la receta entera. Va con @Modifying y flush explicito porque
     * el admin guarda la lista nueva justo despues: si el borrado queda
     * pendiente en el contexto, los INSERT nuevos chocan contra las filas
     * viejas que Hibernate todavia cree que existen.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ProductoTopping pt WHERE pt.idProducto = :idProducto")
    void deleteByIdProducto(@Param("idProducto") Integer idProducto);
}