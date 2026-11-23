package com.lastulas.ica;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Turno de caja con arqueo (punto 11).
 *
 * Antes no habia apertura ni cierre: el cajero entraba, cobraba y se iba, y
 * nadie comparaba nunca el efectivo declarado contra el contado. El arqueo se
 * calcula, no se escribe:
 *
 *   declarado = monto_inicial + SUM(ventas.monto_efectivo del turno)
 *   diferencia = efectivo_contado - declarado
 *
 * Un sobrante sale positivo y un faltante negativo. Guardar los totales al
 * cerrar es lo que permite que el reporte de un turno viejo siga siendo el
 * mismo aunque despues se corrija una venta.
 */
@Entity
@Table(name = "cajas_turno")
public class CajaTurno {

    public static final String ABIERTA = "ABIERTA";
    public static final String CERRADA = "CERRADA";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_caja_turno")
    private Integer idCajaTurno;

    @Column(name = "id_usuario", nullable = false)
    private Integer idUsuario;

    @Column(name = "fecha_apertura", nullable = false)
    private LocalDateTime fechaApertura = LocalDateTime.now();

    @Column(name = "fecha_cierre")
    private LocalDateTime fechaCierre;

    @Column(name = "monto_inicial", nullable = false, precision = 10, scale = 2)
    private BigDecimal montoInicial = BigDecimal.ZERO;

    /** Lo que el cajero conto en la gaveta al cerrar. */
    @Column(name = "efectivo_contado", precision = 10, scale = 2)
    private BigDecimal efectivoContado;

    @Column(name = "total_ventas", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalVentas = BigDecimal.ZERO;

    @Column(name = "total_boletas", nullable = false)
    private Integer totalBoletas = 0;

    @Column(name = "total_efectivo", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalEfectivo = BigDecimal.ZERO;

    @Column(name = "total_digital", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalDigital = BigDecimal.ZERO;

    @Column(name = "diferencia", precision = 10, scale = 2)
    private BigDecimal diferencia;

    @Column(name = "observaciones", length = 300)
    private String observaciones;

    @Column(name = "estado", nullable = false, length = 20)
    private String estado = ABIERTA;

    public CajaTurno() {}

    public boolean estaAbierta() { return ABIERTA.equalsIgnoreCase(estado); }

    /** Efectivo que el sistema espera que haya en la gaveta. */
    public BigDecimal efectivoEsperado() {
        BigDecimal inicial = montoInicial != null ? montoInicial : BigDecimal.ZERO;
        BigDecimal cobrado = totalEfectivo != null ? totalEfectivo : BigDecimal.ZERO;
        return inicial.add(cobrado);
    }

    public Integer getIdCajaTurno() { return idCajaTurno; }
    public void setIdCajaTurno(Integer idCajaTurno) { this.idCajaTurno = idCajaTurno; }

    public Integer getIdUsuario() { return idUsuario; }
    public void setIdUsuario(Integer idUsuario) { this.idUsuario = idUsuario; }

    public LocalDateTime getFechaApertura() { return fechaApertura; }
    public void setFechaApertura(LocalDateTime fechaApertura) { this.fechaApertura = fechaApertura; }

    public LocalDateTime getFechaCierre() { return fechaCierre; }
    public void setFechaCierre(LocalDateTime fechaCierre) { this.fechaCierre = fechaCierre; }

    public BigDecimal getMontoInicial() { return montoInicial; }
    public void setMontoInicial(BigDecimal montoInicial) { this.montoInicial = montoInicial; }

    public BigDecimal getEfectivoContado() { return efectivoContado; }
    public void setEfectivoContado(BigDecimal efectivoContado) { this.efectivoContado = efectivoContado; }

    public BigDecimal getTotalVentas() { return totalVentas; }
    public void setTotalVentas(BigDecimal totalVentas) { this.totalVentas = totalVentas; }

    public Integer getTotalBoletas() { return totalBoletas; }
    public void setTotalBoletas(Integer totalBoletas) { this.totalBoletas = totalBoletas; }

    public BigDecimal getTotalEfectivo() { return totalEfectivo; }
    public void setTotalEfectivo(BigDecimal totalEfectivo) { this.totalEfectivo = totalEfectivo; }

    public BigDecimal getTotalDigital() { return totalDigital; }
    public void setTotalDigital(BigDecimal totalDigital) { this.totalDigital = totalDigital; }

    public BigDecimal getDiferencia() { return diferencia; }
    public void setDiferencia(BigDecimal diferencia) { this.diferencia = diferencia; }

    public String getObservaciones() { return observaciones; }
    public void setObservaciones(String observaciones) { this.observaciones = observaciones; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}