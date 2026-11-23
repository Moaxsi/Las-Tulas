package com.lastulas.ica.dto;

import com.lastulas.ica.AdicionalCobrado;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lo que el POS recibe de vuelta tras cobrar. Muestra el desglose fiscal
 * tal como lo calculo el servidor, para que la pantalla de pago coincida
 * con lo que queda guardado (punto 12).
 */
public record VentaResponse(
        Integer idVenta,
        String numeroComprobante,
        String estado,
        LocalDateTime fechaVenta,
        String clienteNombre,
        String clienteDocumento,
        String tipoComprobante,
        String metodoPago,

        /** Base imponible sin IGV. */
        BigDecimal subtotal,
        /** Impuesto incluido dentro del total. */
        BigDecimal igv,
        /** Tasa aplicada, para mostrarla en la pantalla. */
        BigDecimal tasaIgv,
        /** Total cobrado. Incluye IGV. */
        BigDecimal total,

        BigDecimal montoEfectivo,
        BigDecimal montoDigital,
        BigDecimal vuelto,

        Integer idUsuario,
        String cajeroNombre,
        List<DetalleResponse> detalles
) {

    public record DetalleResponse(
            Integer idDetalle,
            Integer idProducto,
            String nombreProducto,
            Integer cantidad,
            BigDecimal precioUnitario,
            BigDecimal subtotal,
            String toppings,
            String sabores,
            String notas,
            String frutas,
            BigDecimal precioBaseUnitario,
            List<AdicionalCobrado> adicionalesCobrados
    ) {
    }
}
