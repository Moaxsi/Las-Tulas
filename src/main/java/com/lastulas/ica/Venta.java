package com.lastulas.ica;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ventas")
public class Venta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_venta")
    private Integer idVenta;

    @Column(name = "numero_serie")
    private String numeroSerie = "B001";

    /**
     * Correlativo fiscal del comprobante (1, 2, 3...), NO el id de la venta.
     *
     * Antes iba como {@code insertable = false, updatable = false}: Hibernate
     * lo omitia del INSERT y lo dejaba al DEFAULT de la columna, pero sin
     * releerlo el objeto en memoria se quedaba en null, asi que el numero que
     * se imprimia al cajero no era el que quedo guardado. Para un documento
     * legal eso no puede ser, asi que la columna se escribe siempre de forma
     * explicita: el servicio ({@code VentaService}) pide el valor a la secuencia
     * ({@code VentaRepository.siguienteCorrelativo()}) y lo fija con
     * {@link #setNumeroCorrelativo(Integer)} antes de guardar.
     */
    @Column(name = "numero_correlativo", nullable = false)
    private Integer numeroCorrelativo;

    @Column(name = "id_usuario")
    private Integer idUsuario = 2;

    @Column(name = "cliente_nombre", nullable = false)
    private String clienteNombre;

    @Column(name = "cliente_documento")
    private String clienteDocumento;

    @Column(name = "tipo_comprobante", nullable = false)
    private String tipoComprobante;

    @Column(name = "metodo_pago", nullable = false)
    private String metodoPago;

    @Column(name = "monto_efectivo")
    private BigDecimal montoEfectivo = BigDecimal.ZERO;

    @Column(name = "monto_digital")
    private BigDecimal montoDigital = BigDecimal.ZERO;

    /**
     * Base imponible SIN IGV. La calcula el servidor, nunca el navegador:
     * el total manda y aqui se guarda el desglose fiscal (punto 12).
     */
    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    /**
     * Impuesto que ya esta incluido en {@link #total}. Es total - subtotal.
     */
    @Column(name = "igv", nullable = false, precision = 12, scale = 2)
    private BigDecimal igv = BigDecimal.ZERO;

    @Column(name = "tasa_igv", precision = 10, scale = 6)
    private BigDecimal tasaIgv;

    /**
     * Total cobrado al cliente. Incluye IGV porque el precio de carta ya
     * lo incluye, asi que este valor NO cambia respecto al flujo anterior.
     */
    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    /** Efectivo sobrante devuelto al cliente. No es ingreso: se guarda para el arqueo (punto 22). */
    @Column(name = "vuelto", nullable = false, precision = 12, scale = 2)
    private BigDecimal vuelto = BigDecimal.ZERO;

    /**
     * EMITIDA o ANULADA. Reemplaza al DELETE fisico (punto 11).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoVenta estado = EstadoVenta.EMITIDA;

    /**
     * Fecha y hora del cobro. Se escribe en el INSERT (antes era solo lectura),
     * de modo que lo que se responde al cajero es exactamente lo guardado y
     * no hace falta un fallback con LocalDateTime.now() al pintar la fecha.
     */
    @Column(name = "fecha_venta")
    private LocalDateTime fechaVenta = LocalDateTime.now();

    @Column(name = "fecha_anulacion")
    private LocalDateTime fechaAnulacion;

    @Column(name = "motivo_anulacion", length = 200)
    private String motivoAnulacion;

    @Column(name = "id_usuario_anulacion")
    private Integer idUsuarioAnulacion;

    /**
     * Turno de caja al que pertenece esta venta (punto 11).
     *
     * Es lo que permite cerrar caja con arqueo: el turno sabe que ventas son
     * suyas y puede sumar el efectivo de todas juntas. NULL si la venta se
     * cobro sin ningun turno abierto, que es lo que pasaba antes de que este
     * modulo existiera.
     */
    @Column(name = "id_caja_turno")
    private Integer idCajaTurno;

    @Column(name = "clave_idempotencia", length = 36)
    private String claveIdempotencia;

    @Column(name = "huella_idempotencia", length = 64)
    private String huellaIdempotencia;

    public Venta() {}

    public Integer getIdVenta() { return idVenta; }
    public void setIdVenta(Integer idVenta) { this.idVenta = idVenta; }

    public String getNumeroSerie() { return numeroSerie; }
    public void setNumeroSerie(String numeroSerie) { this.numeroSerie = numeroSerie; }

    public Integer getNumeroCorrelativo() { return numeroCorrelativo; }
    public void setNumeroCorrelativo(Integer numeroCorrelativo) { this.numeroCorrelativo = numeroCorrelativo; }

    public Integer getIdUsuario() { return idUsuario; }
    public void setIdUsuario(Integer idUsuario) { this.idUsuario = idUsuario; }

    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }

    public String getClienteDocumento() { return clienteDocumento; }
    public void setClienteDocumento(String clienteDocumento) { this.clienteDocumento = clienteDocumento; }

    public String getTipoComprobante() { return tipoComprobante; }
    public void setTipoComprobante(String tipoComprobante) { this.tipoComprobante = tipoComprobante; }

    public String getMetodoPago() { return metodoPago; }
    public void setMetodoPago(String metodoPago) { this.metodoPago = metodoPago; }

    public BigDecimal getMontoEfectivo() { return montoEfectivo; }
    public void setMontoEfectivo(BigDecimal montoEfectivo) { this.montoEfectivo = montoEfectivo; }

    public BigDecimal getMontoDigital() { return montoDigital; }
    public void setMontoDigital(BigDecimal montoDigital) { this.montoDigital = montoDigital; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getIgv() { return igv; }
    public void setIgv(BigDecimal igv) { this.igv = igv; }

    public BigDecimal getTasaIgv() { return tasaIgv; }
    public void setTasaIgv(BigDecimal tasaIgv) { this.tasaIgv = tasaIgv; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public BigDecimal getVuelto() { return vuelto; }
    public void setVuelto(BigDecimal vuelto) { this.vuelto = vuelto; }

    public EstadoVenta getEstado() { return estado; }
    public void setEstado(EstadoVenta estado) { this.estado = estado; }

    public LocalDateTime getFechaVenta() { return fechaVenta; }
    public void setFechaVenta(LocalDateTime fechaVenta) { this.fechaVenta = fechaVenta; }

    public LocalDateTime getFechaAnulacion() { return fechaAnulacion; }
    public void setFechaAnulacion(LocalDateTime fechaAnulacion) { this.fechaAnulacion = fechaAnulacion; }

    public String getMotivoAnulacion() { return motivoAnulacion; }
    public void setMotivoAnulacion(String motivoAnulacion) { this.motivoAnulacion = motivoAnulacion; }

    public Integer getIdUsuarioAnulacion() { return idUsuarioAnulacion; }
    public void setIdUsuarioAnulacion(Integer idUsuarioAnulacion) { this.idUsuarioAnulacion = idUsuarioAnulacion; }

    public Integer getIdCajaTurno() { return idCajaTurno; }
    public void setIdCajaTurno(Integer idCajaTurno) { this.idCajaTurno = idCajaTurno; }

    public String getClaveIdempotencia() { return claveIdempotencia; }
    public void setClaveIdempotencia(String claveIdempotencia) { this.claveIdempotencia = claveIdempotencia; }

    public String getHuellaIdempotencia() { return huellaIdempotencia; }
    public void setHuellaIdempotencia(String huellaIdempotencia) { this.huellaIdempotencia = huellaIdempotencia; }

    /** Numero de comprobante tal como lo muestra el POS: B001-000123. */
    public String getNumeroComprobante() {
        int correlativo = numeroCorrelativo != null ? numeroCorrelativo : (idVenta != null ? idVenta : 0);
        return (numeroSerie != null ? numeroSerie : "B001") + "-" + String.format("%06d", correlativo);
    }
}
