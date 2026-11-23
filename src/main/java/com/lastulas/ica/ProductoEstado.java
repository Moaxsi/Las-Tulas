package com.lastulas.ica;

/**
 * Reglas de "estado" de un producto. Vive en una clase propia porque el mismo
 * criterio se usa en dos lugares que no se pueden llamar entre si: el
 * controlador (cuando el admin crea o edita) y el UPDATE atomico del stock
 * (cuando una venta deja el producto en cero). Si las reglas estuvieran
 * duplicadas, algun dia uno de los dos caminos se queda sin actualizar.
 */
public final class ProductoEstado {

    public static final String DISPONIBLE = "Disponible";
    public static final String STOCK_BAJO = "Stock bajo";
    public static final String AGOTADO = "Agotado";

    /** A partir de este stock el producto pasa a "Stock bajo". */
    public static final int UMBRAL_STOCK_BAJO = 10;

    private ProductoEstado() {}

    public static String estadoSegunStock(Integer stock) {
        if (stock == null || stock <= 0) return AGOTADO;
        if (stock <= UMBRAL_STOCK_BAJO) return STOCK_BAJO;
        return DISPONIBLE;
    }
}
