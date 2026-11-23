package com.lastulas.ica;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** Catalogo de tipos de comprobante (punto 7). */
public interface TipoComprobanteCatalogoRepository extends JpaRepository<TipoComprobanteCatalogo, Integer> {
    List<TipoComprobanteCatalogo> findAllByOrderByIdTipoComprobanteAsc();
}