package com.lastulas.ica;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface VentaRepository extends JpaRepository<Venta, Integer>,
        JpaSpecificationExecutor<Venta> {

    Optional<Venta> findByClaveIdempotencia(String claveIdempotencia);

    List<Venta> findAllByOrderByIdVentaDesc();

    /**
     * Siguiente correlativo del comprobante (B001-000001, B001-000002, ...).
     *
     * Se pide explicitamente a la secuencia en vez de dejarlo al DEFAULT de
     * la columna, por una razon concreta: cuando el INSERT omite la columna
     * y la pone la base, Hibernate no vuelve a leerla, asi que el objeto en
     * memoria queda con numeroCorrelativo = null. El ticket que se le
     * imprimia al cajero caia entonces en el id de la venta, que es un
     * numero distinto al realmente guardado. Para un comprobante fiscal
     * eso no puede ser: el numero que ve el cliente tiene que ser
     * exactamente el que quedo en la base.
     */
    @Query(value = "SELECT nextval('ventas_numero_correlativo_seq')", nativeQuery = true)
    Long siguienteCorrelativo();

    @Query(value = "SELECT nextval('ventas_nota_venta_correlativo_seq')", nativeQuery = true)
    Long siguienteCorrelativoNotaVenta();

    // =================================================================
    // AGREGACIONES PARA LOS REPORTES (punto 4)
    //
    // Antes el backend devolvia la tabla entera y cada pantalla sumaba en
    // JavaScript. Con meses de datos eso son cientos de miles de filas por
    // cada F5 y la suma en el navegador. Aqui se calcula con SUM y COUNT en
    // PostgreSQL y solo viaja el resultado.
    //
    // Las agregaciones se arman en ReporteService con la Criteria API, excepto
    // la serie diaria, que usa SQL PostgreSQL para agrupar timestamps por fecha.
    // =================================================================

    /**
     * Totales de la pagina que se esta mostrando, para que la barra de
     * resumen no tenga que recargar la tabla entera.
     */
    @Query("SELECT COALESCE(SUM(v.total), 0), COALESCE(SUM(v.montoEfectivo), 0), COALESCE(SUM(v.montoDigital), 0) "
         + "FROM Venta v "
         + "WHERE v.idVenta IN :ids AND v.estado = :estado")
    Object[] totalesDePagina(@Param("ids") List<Integer> ids, @Param("estado") EstadoVenta estado);

    @Query(value = """
            SELECT CAST(fecha_venta AS DATE), COALESCE(SUM(total), 0), COUNT(*)
            FROM ventas
            WHERE estado = :estado
              AND fecha_venta >= :desde
              AND fecha_venta < :hasta
            GROUP BY CAST(fecha_venta AS DATE)
            ORDER BY CAST(fecha_venta AS DATE)
            """, nativeQuery = true)
    List<Object[]> totalesDiarios(@Param("estado") String estado,
                                  @Param("desde") LocalDateTime desde,
                                  @Param("hasta") LocalDateTime hasta);

    Page<Venta> findAllByOrderByIdVentaDesc(Pageable pageable);
}
