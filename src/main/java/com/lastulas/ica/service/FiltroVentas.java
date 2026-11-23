package com.lastulas.ica.service;

import java.time.LocalDate;

/**
 * Filtros de los reportes de ventas (punto 4).
 *
 * Se filtran en el servidor y no en el navegador. Antes el backend mandaba la
 * tabla entera y cada pantalla recurria sobre el array: el filtro se aplicaba
 * DESPUES de descargar todo, asi que no ahorraba nada y ademas el total que
 * mostraba la pantalla podia no coincidir con lo que habia pedido.
 *
 * cajaNumero viaja como idUsuario, no como "Caja 3". El texto "Caja N" depende
 * de la lista de cajeros del momento en que se armo; el id_usuario es lo
 * unico estable.
 */
public record FiltroVentas(
        String buscar,
        LocalDate desde,
        LocalDate hasta,
        Integer idUsuario,
        String metodoPago,
        String tipoComprobante,
        String estado,
        String numeroComprobante
) {

    public static FiltroVentas vacio() {
        return new FiltroVentas(null, null, null, null, null, null, null, null);
    }

    /** Estado a filtrar, o null para no filtrar por estado. */
    public com.lastulas.ica.EstadoVenta estadoNormalizado() {
        if (estado == null || estado.isBlank()) {
            return null;
        }
        try {
            return com.lastulas.ica.EstadoVenta.valueOf(estado.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}