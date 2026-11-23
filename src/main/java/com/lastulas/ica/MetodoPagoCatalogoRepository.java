package com.lastulas.ica;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** Catalogo de metodos de pago (punto 7). */
public interface MetodoPagoCatalogoRepository extends JpaRepository<MetodoPagoCatalogo, Integer> {
    List<MetodoPagoCatalogo> findAllByOrderByIdMetodoPagoAsc();
}