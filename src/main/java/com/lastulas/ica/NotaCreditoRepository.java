package com.lastulas.ica;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface NotaCreditoRepository extends JpaRepository<NotaCredito, Integer> {

    Optional<NotaCredito> findByIdVenta(Integer idVenta);

    List<NotaCredito> findByIdUsuarioAnulacionOrderByFechaAnulacionDesc(Integer idUsuarioAnulacion);

    /** Siguiente correlativo de nota de credito, independiente del de las boletas. */
    @Query(value = "SELECT nextval('seq_nota_credito')", nativeQuery = true)
    Long siguienteCorrelativo();
}
