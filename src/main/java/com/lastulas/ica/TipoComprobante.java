package com.lastulas.ica;

/**
 * Tipos de comprobante canonicos (punto 7).
 *
 * Mismo motivo que {@link MetodoPago}: la columna era texto libre y una
 * boleta podia quedar como 'Boleta con DNI' o 'BOLETA CON DNI' sin que nadie
 * lo notara hasta que el reporte contaba dos veces la misma cosa.
 *
 * Cada tipo sabe que documento exige, asi que el servidor puede rechazar una
 * factura sin RUC en vez de guardar un comprobante que SUNAT no aceptaria.
 */
public enum TipoComprobante {

    BOLETA_SIMPLE("Boleta simple", false, false),
    BOLETA_DNI("Boleta con DNI", true, false),
    FACTURA("Factura", false, true),
    NOTA_VENTA("Nota de venta", false, false);

    private final String nombre;
    private final boolean exigeDocumento;
    private final boolean esFactura;

    TipoComprobante(String nombre, boolean exigeDocumento, boolean esFactura) {
        this.nombre = nombre;
        this.exigeDocumento = exigeDocumento;
        this.esFactura = esFactura;
    }

    public String getNombre() { return nombre; }

    /** true si sin DNI/RUC el comprobante no se puede emitir. */
    public boolean exigeDocumento() { return exigeDocumento; }

    /** true si el documento debe ser un RUC de 11 digitos. */
    public boolean esFactura() { return esFactura; }

    /**
     * Tipo de documento que espera este comprobante: "ruc" para factura,
     * "dni" para boleta con DNI y null cuando no hace falta documento.
     */
    public String tipoDocumentoEsperado() {
        return esFactura ? "ruc" : (exigeDocumento ? "dni" : null);
    }

    public static TipoComprobante desde(String texto) {
        if (texto == null || texto.isBlank()) {
            return BOLETA_SIMPLE;
        }
        String normalizado = texto.trim()
                .toUpperCase()
                .replace(".", "")
                .replace("-", "_")
                .replace(" ", "_")
                .replaceAll("_+", "_");

        for (TipoComprobante t : values()) {
            if (t.name().equals(normalizado)) {
                return t;
            }
        }
        if (normalizado.equals("NOTA_DE_VENTA")) return NOTA_VENTA;
        if (normalizado.contains("FACTURA")) return FACTURA;
        if (normalizado.contains("DNI")) return BOLETA_DNI;
        return BOLETA_SIMPLE;
    }
}
