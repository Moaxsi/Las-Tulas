package com.lastulas.ica;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "detalle_ventas")
public class DetalleVenta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_detalle")
    private Integer idDetalle;

    @Column(name = "id_venta", nullable = false)
    private Integer idVenta;

    @Column(name = "id_producto")
    private Integer idProducto;

    @Column(name = "nombre_producto", nullable = false)
    private String nombreProducto;

    @Column(name = "cantidad", nullable = false)
    private Integer cantidad;

    @Column(name = "precio_unitario", nullable = false)
    private BigDecimal precioUnitario;

    @Column(name = "subtotal", nullable = false)
    private BigDecimal subtotal;

    @Column(name = "precio_base_unitario")
    private BigDecimal precioBaseUnitario;

    @Convert(converter = AdicionalesCobradosConverter.class)
    @Column(name = "adicionales_cobrados", columnDefinition = "TEXT")
    private List<AdicionalCobrado> adicionalesCobrados;

    public BigDecimal getPrecioBaseUnitario() { return precioBaseUnitario; }
    public void setPrecioBaseUnitario(BigDecimal valor) { precioBaseUnitario = valor; }
    public List<AdicionalCobrado> getAdicionalesCobrados() {
        return adicionalesCobrados == null ? List.of() : adicionalesCobrados;
    }
    public void setAdicionalesCobrados(List<AdicionalCobrado> valor) { adicionalesCobrados = List.copyOf(valor); }

    @Column(name = "toppings_detalle")
    private String toppingsDetalle;

    /**
     * Ids de los productos topping de esta linea, separados por coma.
     * Sin esto la anulacion no podria devolver el stock de los toppings,
     * porque toppingsDetalle solo guarda los nombres.
     */
    @Column(name = "toppings_ids", length = 300)
    private String toppingsIds;

    @Column(name = "sabores_detalle", columnDefinition = "TEXT")
    private String saboresDetalle;

    @Column(name = "sabores_ids", columnDefinition = "TEXT")
    private String saboresIds;

    @Column(name = "notas", length = 300)
    private String notas;

    @Column(name = "frutas_detalle", columnDefinition = "TEXT")
    private String frutasDetalle;
    @Column(name = "frutas_ids", columnDefinition = "TEXT")
    private String frutasIds;
    public String getFrutasDetalle() { return frutasDetalle; }
    public void setFrutasDetalle(String value) { frutasDetalle = value; }
    public String getFrutasIds() { return frutasIds; }
    public void setFrutasIds(String value) { frutasIds = value; }

    public DetalleVenta() {}

    public String getNotas() { return notas; }
    public void setNotas(String notas) { this.notas = notas; }

    public String getSaboresDetalle() { return saboresDetalle; }
    public void setSaboresDetalle(String saboresDetalle) { this.saboresDetalle = saboresDetalle; }
    public String getSaboresIds() { return saboresIds; }
    public void setSaboresIds(String saboresIds) { this.saboresIds = saboresIds; }

    public Integer getIdDetalle() { return idDetalle; }
    public void setIdDetalle(Integer idDetalle) { this.idDetalle = idDetalle; }

    public Integer getIdVenta() { return idVenta; }
    public void setIdVenta(Integer idVenta) { this.idVenta = idVenta; }

    public Integer getIdProducto() { return idProducto; }
    public void setIdProducto(Integer idProducto) { this.idProducto = idProducto; }

    public String getNombreProducto() { return nombreProducto; }
    public void setNombreProducto(String nombreProducto) { this.nombreProducto = nombreProducto; }

    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }

    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public String getToppingsDetalle() { return toppingsDetalle; }
    public void setToppingsDetalle(String toppingsDetalle) { this.toppingsDetalle = toppingsDetalle; }

    public String getToppingsIds() { return toppingsIds; }
    public void setToppingsIds(String toppingsIds) { this.toppingsIds = toppingsIds; }
}
