package com.lastulas.ica;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "productos")
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_producto")
    private Integer idProducto;

    @Column(name = "referencia", nullable = false, unique = true, length = 30)
    private String referencia;

    @Column(name = "nombre_producto", nullable = false, length = 150)
    private String nombreProducto;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "precio", nullable = false, precision = 10, scale = 2)
    private BigDecimal precio;

    @Column(name = "stock")
    private Integer stock = 0;

    @Column(name = "id_categoria", nullable = false)
    private Integer idCategoria;

    @Column(name = "estado", length = 20)
    private String estado = "Disponible";

    @Column(name = "url_imagen", columnDefinition = "TEXT")
    private String urlImagen = "lastulas.jpg";

    /**
     * Alta logica del producto (punto 9).
     *
     * false = dado de baja: sale de la carta y del POS, pero la fila sigue
     * existiendo. Borrarla fisicamente se llevaba por delante las relaciones
     * de producto_toppings y dejaba el historial de ventas apuntando a un id
     * que no existe. No se confunde con "estado", que describe el STOCK
     * (Disponible / Stock bajo / Agotado), no la existencia del producto.
     */
    @Column(name = "activo", nullable = false)
    private Boolean activo = Boolean.TRUE;

    @Column(name = "fecha_creacion", insertable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "adicional_categorias", joinColumns = @JoinColumn(name = "id_adicional"))
    @Column(name = "id_categoria", nullable = false)
    private Set<Integer> categoriasDestino = new LinkedHashSet<>();

    @Column(name = "toppings_incluidos", nullable = false)
    private Integer toppingsIncluidos = 0;
    @Column(name = "frutas_incluidas", nullable = false)
    private Integer frutasIncluidas = 0;
    public Integer getToppingsIncluidos() { return toppingsIncluidos; }
    public void setToppingsIncluidos(Integer value) { toppingsIncluidos = value; }
    public Integer getFrutasIncluidas() { return frutasIncluidas; }
    public void setFrutasIncluidas(Integer value) { frutasIncluidas = value; }

    public Producto() {}

    public Set<Integer> getCategoriasDestino() { return categoriasDestino; }
    public void setCategoriasDestino(Set<Integer> categoriasDestino) {
        this.categoriasDestino = categoriasDestino == null ? new LinkedHashSet<>() : new LinkedHashSet<>(categoriasDestino);
    }

    public Integer getIdProducto() { return idProducto; }
    public void setIdProducto(Integer idProducto) { this.idProducto = idProducto; }

    public String getReferencia() { return referencia; }
    public void setReferencia(String referencia) { this.referencia = referencia; }

    public String getNombreProducto() { return nombreProducto; }
    public void setNombreProducto(String nombreProducto) { this.nombreProducto = nombreProducto; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public BigDecimal getPrecio() { return precio; }
    public void setPrecio(BigDecimal precio) { this.precio = precio; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public Integer getIdCategoria() { return idCategoria; }
    public void setIdCategoria(Integer idCategoria) { this.idCategoria = idCategoria; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getUrlImagen() { return urlImagen; }
    public void setUrlImagen(String urlImagen) { this.urlImagen = urlImagen; }

    public Boolean getActivo() { return activo; }
    public void setActivo(Boolean activo) { this.activo = activo; }

    /** null se trata como true: si la columna no vino, el producto esta vivo. */
    public boolean estaActivo() { return !Boolean.FALSE.equals(activo); }

    public LocalDateTime getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(LocalDateTime fechaCreacion) { this.fechaCreacion = fechaCreacion; }
}
