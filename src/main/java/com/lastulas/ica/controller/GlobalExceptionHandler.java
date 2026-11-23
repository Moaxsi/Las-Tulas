package com.lastulas.ica.controller;

import com.lastulas.ica.service.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Convierte los errores en respuestas que el POS sabe mostrar. El punto de
 * esto es que "No hay stock suficiente de Tela Achocolatada" llegue como
 * mensaje legible al cajero y no como un stack trace de 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException e) {
        return ResponseEntity.status(e.getHttpStatus())
                .body(Map.of("success", false, "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validacion(MethodArgumentNotValidException e) {
        FieldError primerError = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String mensaje = primerError != null ? primerError.getDefaultMessage() : "Datos de la solicitud invalidos.";
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", mensaje));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> sinPermisos(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("success", false, "message", "No tienes permisos para esta operacion."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> inesperado(Exception e) {
        log.error("Error no controlado", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false,
                        "message", "Error interno del servidor. Revisa la consola del backend."));
    }
}
