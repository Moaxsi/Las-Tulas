package com.lastulas.ica.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializa apertura, cobros, movimientos y cierre sin cambiar el esquema existente. */
@Component
public class CajaOperacionLock {
    private final EntityManager em;

    public CajaOperacionLock(EntityManager em) { this.em = em; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void adquirir() {
        // También protege la apertura cuando todavía no existe una fila que bloquear.
        em.createNativeQuery("LOCK TABLE cajas_turno IN SHARE ROW EXCLUSIVE MODE").executeUpdate();
    }
}
