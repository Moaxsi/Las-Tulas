package com.lastulas.ica;

/**
 * Estado fiscal de una venta. Una venta NUNCA se borra: se anula.
 * El DELETE fisico de la tabla ventas era el problema del punto 11.
 */
public enum EstadoVenta {

    EMITIDA,

    ANULADA;

    public boolean estaEmitida() {
        return this == EMITIDA;
    }
}
