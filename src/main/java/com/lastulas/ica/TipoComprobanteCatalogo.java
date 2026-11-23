package com.lastulas.ica;

import jakarta.persistence.*;

/**
 * Fila de la tabla tipos_comprobante. Mismo motivo que
 * {@link MetodoPagoCatalogo}: sin entidad, el tipo de comprobante era texto
 * libre (punto 7).
 */
@Entity
@Table(name = "tipos_comprobante")
public class TipoComprobanteCatalogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_tipo_comprobante")
    private Integer idTipoComprobante;

    @Column(name = "nombre_comprobante", nullable = false, length = 50)
    private String nombreComprobante;

    /** Codigo canonico que se guarda en ventas.tipo_comprobante. */
    @Column(name = "codigo", length = 30)
    private String codigo;

    public TipoComprobanteCatalogo() {}

    public Integer getIdTipoComprobante() { return idTipoComprobante; }
    public void setIdTipoComprobante(Integer idTipoComprobante) { this.idTipoComprobante = idTipoComprobante; }

    public String getNombreComprobante() { return nombreComprobante; }
    public void setNombreComprobante(String nombreComprobante) { this.nombreComprobante = nombreComprobante; }

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    public TipoComprobante asEnum() {
        return codigo == null ? null : TipoComprobante.desde(codigo);
    }
}