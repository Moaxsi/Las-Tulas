package com.lastulas.ica;

import jakarta.persistence.*;

/**
 * Constante de configuracion que se lee de la tabla "parametros".
 * Sirve para cambiar la tasa de IGV sin recompilar ni redeployar.
 */
@Entity
@Table(name = "parametros")
public class Parametro {

    @Id
    @Column(name = "clave", length = 40)
    private String clave;

    @Column(name = "valor", nullable = false, length = 100)
    private String valor;

    @Column(name = "descripcion", length = 200)
    private String descripcion;

    public Parametro() {}

    public Parametro(String clave, String valor, String descripcion) {
        this.clave = clave;
        this.valor = valor;
        this.descripcion = descripcion;
    }

    public String getClave() { return clave; }
    public void setClave(String clave) { this.clave = clave; }

    public String getValor() { return valor; }
    public void setValor(String valor) { this.valor = valor; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
}
