package com.lastulas.ica.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cabecera del cobro (punto 10).
 *
 * Fijate lo que NO esta aqui: total, subtotal, igv ni precioUnitario. Esos
 * valores se calculan en el servidor a partir de la tabla productos. Antes
 * venían en este cuerpo y el backend los guardaba tal cual, asi que bastaba
 * un POST con total: 0.01 para vender la carta completa por un centimo.
 *
 * Tampoco hay idUsuario: el cajero se toma del token JWT.
 */
public record VentaRequest(

        String clienteNombre,
        String clienteDocumento,
        String tipoComprobante,
        String metodoPago,

        /** Parte del pago recibido en efectivo. Ajustado por el servidor si sobra. */
        BigDecimal montoEfectivo,

        /** Parte del pago recibido por POS/Yape. Ajustado por el servidor si sobra. */
        BigDecimal montoDigital,

        @NotEmpty(message = "La venta debe tener al menos un producto")
        @Valid
        List<ItemVentaRequest> items
) {
}
