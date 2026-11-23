package com.lastulas.ica;

import java.math.BigDecimal;

/** Precio por unidad del producto base, conservado al realizar el cobro. */
public record AdicionalCobrado(Integer idProducto, String nombreProducto, BigDecimal precioUnitario) {
}
