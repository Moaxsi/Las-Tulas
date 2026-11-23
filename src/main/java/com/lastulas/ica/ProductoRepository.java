package com.lastulas.ica;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProductoRepository extends JpaRepository<Producto, Integer> {

    @EntityGraph(attributePaths = "categoriasDestino")
    List<Producto> findByIdCategoriaOrderByIdProductoDesc(Integer idCategoria);

    @EntityGraph(attributePaths = "categoriasDestino")
    List<Producto> findAllByOrderByIdProductoDesc();

    // ---- Filtro por baja logica (punto 9). -------------------------------
    // activo = TRUE es lo que ve el POS. El admin puede pedir los dados de
    // baja con ?incluirInactivos=true para ver el historico.

    @EntityGraph(attributePaths = "categoriasDestino")
    List<Producto> findByActivoTrueOrderByIdProductoDesc();

    @EntityGraph(attributePaths = "categoriasDestino")
    List<Producto> findByActivoTrueAndIdCategoriaOrderByIdProductoDesc(Integer idCategoria);

    List<Producto> findByActivoTrueAndIdCategoriaInOrderByIdProductoDesc(List<Integer> categorias);

    // ---- Validacion de alta (punto 8) ------------------------------------
    // IgnoreCase porque la referencia es una clave de negocio: "TUL01" y
    // "tul01" son el mismo producto y el UNIQUE de la base solo mira el
    // texto exacto, asi que sin esto el filtro dejaria pasar la repetida y
    // el error terminaria en un 500.

    @Query("SELECT COUNT(p) FROM Producto p WHERE UPPER(p.referencia) = UPPER(:referencia)")
    long contarPorReferencia(@Param("referencia") String referencia);

    @Query("SELECT COUNT(p) FROM Producto p WHERE UPPER(p.referencia) = UPPER(:referencia) AND p.idProducto <> :id")
    long contarPorReferenciaDistintaDe(@Param("referencia") String referencia, @Param("id") Integer id);

    Optional<Producto> findByReferencia(String referencia);

    /**
     * Descuenta stock de forma ATOMICA (punto 10).
     *
     * El "AND stock >= :cantidad" dentro del WHERE es lo importante: si dos
     * cajeros venden la ultima unidad a la vez, la base de datos serializa las
     * dos sentencias y solo una devuelve 1. La otra devuelve 0 y el backend
     * rechaza la venta. Con un "leer stock, restar, guardar" en Java se
     * perderia una unidad en silencio.
     *
     * No filtra por activo a proposito: si un producto se dio de baja entre la
     * venta y su anulacion, el stock tiene que volver igual.
     *
     * @return 1 si se desconto, 0 si no hay stock suficiente
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Producto p SET p.stock = p.stock - :cantidad, " +
           "p.estado = CASE WHEN p.stock - :cantidad <= 0 THEN 'Agotado' " +
           "WHEN p.stock - :cantidad <= 10 THEN 'Stock bajo' ELSE 'Disponible' END " +
           "WHERE p.idProducto = :id AND p.stock >= :cantidad")
    int descontarStock(@Param("id") Integer id, @Param("cantidad") Integer cantidad);

    /**
     * Devuelve el stock al anular una venta, y de paso saca el "Agotado".
     *
     * El estado se recalcula DENTRO del mismo UPDATE, no con un segundo query
     * despues. Si se hiciera aparte, entre el descuento de stock y el cambio de
     * estado podria entrar otra venta y dejar el producto en "Agotado" con
     * stock disponible, o al reves. Ademas se evita el N+1.
     *
     * OJO con JPQL: dentro del SET, "p.stock" es el valor ANTIGUO, asi que
     * "p.stock - :cantidad" es el nuevo. Por eso el CASE compara sobre la
     * operacion, no sobre p.stock a secas.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Producto p SET p.stock = p.stock + :cantidad, " +
           "p.estado = CASE WHEN p.stock + :cantidad <= 0 THEN 'Agotado' " +
           "WHEN p.stock + :cantidad <= 10 THEN 'Stock bajo' ELSE 'Disponible' END " +
           "WHERE p.idProducto = :id")
    int devolverStock(@Param("id") Integer id, @Param("cantidad") Integer cantidad);

    /**
     * Trae varios productos en UNA sola consulta. La venta necesita el precio
     * real de cada linea; pedirlos uno por uno seria el patron N+1 otra vez.
     *
     * No filtra por activo: una venta puede referenciar un producto que se dio
     * de baja despues, y el comprobante tiene que salir igual.
     */
    @Query("SELECT p FROM Producto p WHERE p.idProducto IN :ids")
    @EntityGraph(attributePaths = "categoriasDestino")
    List<Producto> findByIdIn(@Param("ids") List<Integer> ids);
}
