package com.lastulas.ica;

import jakarta.persistence.*;

/**
 * Fila de la tabla metodos_pago. Antes no tenia entidad JPA y era solo
 * documentacion de datos (punto 7): el metodo de pago viajaba como texto y
 * nada comprobaba que lo que llegaba estuviera en el catalogo.
 *
 * El enum {@link MetodoPago} es la autoridad sobre el codigo; esta entidad
 * deja ver que hay configurado y expone el catalogo al admin.
 */
@Entity
@Table(name = "metodos_pago")
public class MetodoPagoCatalogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_metodo_pago")
    private Integer idMetodoPago;

    @Column(name = "nombre_metodo", nullable = false, length = 50)
    private String nombreMetodo;

    /** Codigo canonico que se guarda en ventas.metodo_pago. */
    @Column(name = "codigo", length = 30)
    private String codigo;

    public MetodoPagoCatalogo() {}

    public Integer getIdMetodoPago() { return idMetodoPago; }
    public void setIdMetodoPago(Integer idMetodoPago) { this.idMetodoPago = idMetodoPago; }

    public String getNombreMetodo() { return nombreMetodo; }
    public void setNombreMetodo(String nombreMetodo) { this.nombreMetodo = nombreMetodo; }

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    /** Enum que corresponde a esta fila, o null si el codigo no es canonico. */
    public MetodoPago asEnum() {
        return codigo == null ? null : MetodoPago.desde(codigo);
    }
}