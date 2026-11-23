package com.lastulas.ica.service;

/**
 * Error de negocio: no es un bug, es una operacion que no se puede completar
 * (stock insuficiente, venta ya anulada, pago incompleto). Se traduce a 400
 * con un mensaje que el cajero puede entender y mostrar tal cual.
 */
public class BusinessException extends RuntimeException {

    private final int httpStatus;

    public BusinessException(String mensaje) {
        this(mensaje, 400);
    }

    public BusinessException(String mensaje, int httpStatus) {
        super(mensaje);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static BusinessException noEncontrado(String que) {
        return new BusinessException(que + " no encontrado.", 404);
    }
}
