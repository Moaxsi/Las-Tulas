package com.lastulas.ica.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Una linea del pedido. Solo viaja QUE se pidio, nunca CUANTO cuesta:
 * no hay campo de precio ni de subtotal a proposito (punto 10).
 */
public record ItemVentaRequest(
        @NotNull(message = "Cada linea debe traer idProducto")
        Integer idProducto,

        @NotNull(message = "Cada linea debe traer cantidad")
        @Min(value = 1, message = "La cantidad debe ser al menos 1")
        Integer cantidad,

        @Size(max = 25) List<@NotNull @Positive Integer> toppings,
        @Size(max = 25) List<@NotNull @Positive Integer> sabores,
        @Size(max = 300, message = "La nota admite hasta 300 caracteres") String notas,
        @Size(max = 25) List<@NotNull @Positive Integer> frutas
) {
    public ItemVentaRequest(Integer idProducto, Integer cantidad, List<Integer> toppings, List<Integer> sabores, String notas) {
        this(idProducto, cantidad, toppings, sabores, notas, null);
    }
    public ItemVentaRequest(Integer idProducto, Integer cantidad, List<Integer> toppings) {
        this(idProducto, cantidad, toppings, null, null, null);
    }
    public ItemVentaRequest(Integer idProducto, Integer cantidad, List<Integer> toppings, List<Integer> sabores) {
        this(idProducto, cantidad, toppings, sabores, null, null);
    }
}
