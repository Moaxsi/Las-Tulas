package com.lastulas.ica;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

public interface CajaTurnoRepository extends JpaRepository<CajaTurno, Integer> {

    List<CajaTurno> findByEstadoOrderByFechaAperturaAsc(String estado);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CajaTurno c WHERE c.idUsuario = :idUsuario AND c.estado = 'ABIERTA'")
    Optional<CajaTurno> findAbiertoParaCierre(@Param("idUsuario") Integer idUsuario);

    Optional<CajaTurno> findByIdUsuarioAndEstado(Integer idUsuario, String estado);

    Optional<CajaTurno> findFirstByIdUsuarioAndEstadoOrderByFechaAperturaDesc(Integer idUsuario, String estado);

    List<CajaTurno> findByIdUsuarioOrderByFechaAperturaDesc(Integer idUsuario);

    Page<CajaTurno> findAllByOrderByFechaAperturaDesc(Pageable pageable);

    Page<CajaTurno> findByIdUsuarioOrderByFechaAperturaDesc(Integer idUsuario, Pageable pageable);

    /**
     * Totales del turno calculados en la base (punto 11). El arqueo sale de
     * estos numeros, no de lo que el cajero escriba: si el codigo de caja se
     * equivocara, el arqueo tiene que delatarlo.
     */
    interface TotalesTurno {
        BigDecimal getTotalVentas();
        Long getTotalBoletas();
        BigDecimal getTotalEfectivo();
        BigDecimal getTotalDigital();
    }

    @Query("SELECT COALESCE(SUM(v.total), 0) AS totalVentas, COUNT(v) AS totalBoletas, "
         + "COALESCE(SUM(v.montoEfectivo), 0) AS totalEfectivo, COALESCE(SUM(v.montoDigital), 0) AS totalDigital "
         + "FROM Venta v "
         + "WHERE v.idCajaTurno = :turno AND v.estado = :estado")
    TotalesTurno totalesDelTurno(@Param("turno") Integer idCajaTurno,
                             @Param("estado") EstadoVenta estado);
}
