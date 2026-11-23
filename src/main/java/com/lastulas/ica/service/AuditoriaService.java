package com.lastulas.ica.service;

import com.lastulas.ica.Auditoria;
import com.lastulas.ica.AuditoriaRepository;
import com.lastulas.ica.security.UsuarioPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de auditoria (punto 12).
 *
 * Se escribe DENTRO de la misma transaccion que el cambio que se audita: si la
 * operacion se revierte (por ejemplo, la venta se anula pero falla la nota de
 * credito), el rastro tampoco debe quedar. Por eso la propagacion es REQUIRED y
 * no REQUIRES_NEW.
 *
 * Nunca lanza excepcion. Perder una linea de auditoria es malo, pero tumbar un
 * cobro porque no se pudo escribir en la tabla de auditoria es peor.
 */
@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    private final AuditoriaRepository repo;

    public AuditoriaService(AuditoriaRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void registrar(String tabla, Integer idRegistro, String accion,
                          String valorAnterior, String valorNuevo,
                          String detalle, UsuarioPrincipal quien) {
        try {
            Auditoria a = new Auditoria();
            a.setTabla(tabla);
            a.setIdRegistro(idRegistro);
            a.setAccion(accion);
            a.setValorAnterior(recortar(valorAnterior));
            a.setValorNuevo(recortar(valorNuevo));
            a.setDetalle(recortar(detalle));
            if (quien != null) {
                a.setIdUsuario(quien.getIdUsuario());
                a.setUsuarioNombre(quien.getNombreCompleto());
            }
            repo.save(a);
        } catch (Exception e) {
            log.warn("No se pudo registrar la auditoria de {}#{} ({}): {}",
                    tabla, idRegistro, accion, e.getMessage());
        }
    }

    /** La columna es TEXT, pero un volcado entero no ayuda a nadie. */
    private static String recortar(String valor) {
        if (valor == null) {
            return null;
        }
        return valor.length() > 2000 ? valor.substring(0, 2000) : valor;
    }
}