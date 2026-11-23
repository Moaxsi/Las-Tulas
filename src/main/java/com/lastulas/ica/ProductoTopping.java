package com.lastulas.ica;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * Receta de toppings: que toppings puede llevar cada producto (punto 5).
 *
 * Esta tabla ya existia con 1284 filas pero NO tenia entidad JPA y la app no
 * la consultaba nunca: era documentacion de datos. El POS dejaba combinar
 * cualquier topping con cualquier plato y el servidor no tenia forma de
 * impedirlo, aunque la carta si define que toppings van con cada cosa.
 *
 * Clave primaria compuesta (id_producto, id_topping), tal como esta en la base.
 */
@Entity
@Table(name = "producto_toppings")
@IdClass(ProductoTopping.ProductoToppingId.class)
public class ProductoTopping implements Serializable {

    @Id
    @Column(name = "id_producto")
    private Integer idProducto;

    @Id
    @Column(name = "id_topping")
    private Integer idTopping;

    public ProductoTopping() {}

    public ProductoTopping(Integer idProducto, Integer idTopping) {
        this.idProducto = idProducto;
        this.idTopping = idTopping;
    }

    public Integer getIdProducto() { return idProducto; }
    public void setIdProducto(Integer idProducto) { this.idProducto = idProducto; }

    public Integer getIdTopping() { return idTopping; }
    public void setIdTopping(Integer idTopping) { this.idTopping = idTopping; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductoTopping otra)) return false;
        return Objects.equals(idProducto, otra.idProducto)
                && Objects.equals(idTopping, otra.idTopping);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idProducto, idTopping);
    }

    /** Clave compuesta de producto_toppings. */
    public static class ProductoToppingId implements Serializable {

        private Integer idProducto;
        private Integer idTopping;

        public ProductoToppingId() {}

        public ProductoToppingId(Integer idProducto, Integer idTopping) {
            this.idProducto = idProducto;
            this.idTopping = idTopping;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProductoToppingId otra)) return false;
            return Objects.equals(idProducto, otra.idProducto)
                    && Objects.equals(idTopping, otra.idTopping);
        }

        @Override
        public int hashCode() {
            return Objects.hash(idProducto, idTopping);
        }
    }
}