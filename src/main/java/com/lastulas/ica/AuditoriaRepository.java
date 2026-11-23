package com.lastulas.ica;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long>,
        JpaSpecificationExecutor<Auditoria> {

    List<Auditoria> findByTablaAndIdRegistroOrderByFechaDesc(String tabla, Integer idRegistro);

    List<Auditoria> findByTablaAndAccion(String tabla, String accion);

    Page<Auditoria> findAllByOrderByFechaDesc(Pageable pageable);

    @Query("SELECT a FROM Auditoria a WHERE a.tabla = :tabla OR a.idRegistro = :idRegistro ORDER BY a.fecha DESC")
    List<Auditoria> buscar(@Param("tabla") String tabla, @Param("idRegistro") Integer idRegistro);
}
