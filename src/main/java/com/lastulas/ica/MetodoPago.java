package com.lastulas.ica;

/**
 * Metodos de pago canonicos (punto 7).
 *
 * Antes el metodo de pago era texto libre: la columna ventas.metodo_pago
 * guardaba lo que el navegador mandara, asi que convivian 'Efectivo',
 * 'EFECTIVO' y 'EFECTIVO_COMBINADO' y un reporte por metodo de pago acababa
 * agrupando cadenas distintas que significan exactamente lo mismo.
 *
 * Ahora hay un solo codigo por forma de pago, es el que se guarda, y el
 * nombre es solo la etiqueta que se muestra. El enum es la autoridad: si
 * llega algo que no esta aqui, {@link #desde(String)} lo normaliza a
 * efectivo en vez de inventarse un grupo nuevo.
 */
public enum MetodoPago {

    EFECTIVO("Efectivo", true),
    POS("POS", false),
    YAPE("Yape", false),
    PLIN("Plin", false),
    EFECTIVO_POS("Efectivo + POS", true),
    EFECTIVO_YAPE("Efectivo + Yape", true),
    EFECTIVO_COMBINADO("Efectivo combinado", true);

    private final String nombre;
    private final boolean admiteEfectivo;

    MetodoPago(String nombre, boolean admiteEfectivo) {
        this.nombre = nombre;
        this.admiteEfectivo = admiteEfectivo;
    }

    /** Etiqueta que se muestra en las pantallas y en los comprobantes. */
    public String getNombre() { return nombre; }

    /** true si el cobro tiene una parte en efectivo que va a la gaveta. */
    public boolean admiteEfectivo() { return admiteEfectivo; }

    public static MetodoPago codigo(String codigo) {
        return valorDe(codigo);
    }

    /**
     * Acepta el codigo canonico y tambien las formas que venia usando el
     * POS y los datos viejos ('Efectivo', 'YAPE', 'Efectivo combinado',
     * 'EFECTIVO + POS'...). Devolver EFECTIVO como ultimo recurso es
     * preferible a guardar un texto libre: mantiene el reporte agrupando
     * bien y el arqueo de caja sigue cuadrando.
     */
    public static MetodoPago desde(String texto) {
        if (texto == null || texto.isBlank()) {
            return EFECTIVO;
        }
        String normalizado = texto.trim()
                .toUpperCase()
                .replace("-", "_")
                .replace(" ", "_")
                .replace("+", "_")
                .replaceAll("_+", "_");

        for (MetodoPago m : values()) {
            if (m.name().equals(normalizado)) {
                return m;
            }
        }
        // Conservar el combinado histórico cuando no identifica el canal digital.
        if (normalizado.startsWith("EFECTIVO") && !normalizado.equals("EFECTIVO")) {
            return EFECTIVO_COMBINADO;
        }
        if (normalizado.contains("YAPE")) return YAPE;
        if (normalizado.contains("PLIN")) return PLIN;
        if (normalizado.contains("POS")) return POS;
        return EFECTIVO;
    }

    private static MetodoPago valorDe(String codigo) {
        for (MetodoPago m : values()) {
            if (m.name().equalsIgnoreCase(codigo == null ? "" : codigo.trim())) {
                return m;
            }
        }
        return EFECTIVO;
    }
}
