package com.lastulas.ica;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Constancia de anulacion de una venta. Es el reemplazo del DELETE fisico
 * (punto 11): el comprobante original queda en la tabla ventas marcado
 * ANULADA y aqui queda el respaldo de quien anulo, cuando y por que.
 */
@Entity
@Table(name = "notas_credito")
public class NotaCredito {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_nota_credito")
    private Integer idNotaCredito;

    @Column(name = "id_venta", nullable = false)
    private Integer idVenta;

    @Column(name = "numero_nota_credito", nullable = false, length = 30)
    private String numeroNotaCredito;

    @Column(name = "tipo_comprobante", nullable = false, length = 30)
    private String tipoComprobante = "Nota de credito";

    @Column(name = "motivo", nullable = false, length = 200)
    private String motivo;

    @Column(name = "total_anulado", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAnulado;

    @Column(name = "fecha_anulacion", nullable = false)
    private LocalDateTime fechaAnulacion;

    @Column(name = "id_usuario_anulacion")
    private Integer idUsuarioAnulacion;

    public NotaCredito() {}

    public Integer getIdNotaCredito() { return idNotaCredito; }
    public void setIdNotaCredito(Integer idNotaCredito) { this.idNotaCredito = idNotaCredito; }

    public Integer getIdVenta() { return idVenta; }
    public void setIdVenta(Integer idVenta) { this.idVenta = idVenta; }

    public String getNumeroNotaCredito() { return numeroNotaCredito; }
    public void setNumeroNotaCredito(String numeroNotaCredito) { this.numeroNotaCredito = numeroNotaCredito; }

    public String getTipoComprobante() { return tipoComprobante; }
    public void setTipoComprobante(String tipoComprobante) { this.tipoComprobante = tipoComprobante; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    public BigDecimal getTotalAnulado() { return totalAnulado; }
    public void setTotalAnulado(BigDecimal totalAnulado) { this.totalAnulado = totalAnulado; }

    public LocalDateTime getFechaAnulacion() { return fechaAnulacion; }
    public void setFechaAnulacion(LocalDateTime fechaAnulacion) { this.fechaAnulacion = fechaAnulacion; }

    public Integer getIdUsuarioAnulacion() { return idUsuarioAnulacion; }
    public void setIdUsuarioAnulacion(Integer idUsuarioAnulacion) { this.idUsuarioAnulacion = idUsuarioAnulacion; }
}
