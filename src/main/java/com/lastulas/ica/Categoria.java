package com.lastulas.ica;

import jakarta.persistence.*;

/**
 * Categoria de la carta.
 *
 * esTopping (punto 6) marca las categorias cuyos productos son ADICIONALES y
 * no platos. Antes el POS tenia escrita a mano la categoria 5 (TOPPIN) como
 * unica fuente de toppings y se comia la 7 (TOPPING), cuyos productos se
 * vendian como platos sueltos. Con la columna, tanto el POS como el servidor
 * preguntan que categorias son topping en vez de hardcodear numeros.
 */
@Entity
@Table(name = "categorias")
public class Categoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_categoria")
    private Integer idCategoria;

    @Column(name = "nombre", nullable = false, unique = true, length = 50)
    private String nombre;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "es_topping")
    private Boolean esTopping = Boolean.FALSE;

    @Column(name = "es_sabor", nullable = false)
    private Boolean esSabor = Boolean.FALSE;

    @Column(name = "es_fruta", nullable = false)
    private Boolean esFruta = Boolean.FALSE;
    public Boolean getEsFruta() { return esFruta; }
    public void setEsFruta(Boolean value) { esFruta = value; }
    public boolean esCategoriaFruta() { return Boolean.TRUE.equals(esFruta); }

    public Categoria() {}

    public Integer getIdCategoria() { return idCategoria; }
    public void setIdCategoria(Integer idCategoria) { this.idCategoria = idCategoria; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public Boolean getEsTopping() { return esTopping; }
    public void setEsTopping(Boolean esTopping) { this.esTopping = esTopping; }

    /** null se trata como false: si la columna no vino, no es topping. */
    public boolean esCategoriaTopping() { return Boolean.TRUE.equals(esTopping); }

    public Boolean getEsSabor() { return esSabor; }
    public void setEsSabor(Boolean esSabor) { this.esSabor = esSabor; }
    public boolean esCategoriaSabor() { return Boolean.TRUE.equals(esSabor); }
}
