package com.lastulas.ica;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Historial de cambios administrativos (punto 12).
 *
 * Antes, corregir el metodo de pago de una boleta era un UPDATE en seco: el
 * valor anterior se perdia y no quedaba rastro de quien lo cambio. Una
 * anulacion si dejaba fecha, motivo y usuario, pero en otra tabla y sin
 * historial de cambios.
 *
 * Una fila por cambio con valor_anterior y valor_nuevo. Sin el valor anterior
 * no hay auditoria: solo queda el estado final, que es justo lo que no sirve
 * para saber que paso.
 */
@Entity
@Table(name = "auditoria")
public class Auditoria {

    public static final String TABLA_VENTAS = "ventas";
    public static final String TABLA_PRODUCTOS = "productos";
    public static final String TABLA_CATEGORIAS = "categorias";
    public static final String TABLA_USUARIOS = "usuarios";
    public static final String TABLA_CAJAS = "cajas_turno";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_auditoria")
    private Long idAuditoria;

    @Column(name = "tabla", nullable = false, length = 50)
    private String tabla;

    @Column(name = "id_registro")
    private Integer idRegistro;

    /** CREAR, MODIFICAR, ANULAR, DAR_DE_BAJA, REACTIVAR, ABRIR_CAJA, CERRAR_CAJA... */
    @Column(name = "accion", nullable = false, length = 30)
    private String accion;

    @Column(name = "id_usuario")
    private Integer idUsuario;

    @Column(name = "usuario_nombre", length = 120)
    private String usuarioNombre;

    @Column(name = "valor_anterior", columnDefinition = "TEXT")
    private String valorAnterior;

    @Column(name = "valor_nuevo", columnDefinition = "TEXT")
    private String valorNuevo;

    @Column(name = "detalle", columnDefinition = "TEXT")
    private String detalle;

    @Column(name = "fecha")
    private LocalDateTime fecha = LocalDateTime.now();

    public Auditoria() {}

    public Long getIdAuditoria() { return idAuditoria; }
    public void setIdAuditoria(Long idAuditoria) { this.idAuditoria = idAuditoria; }

    public String getTabla() { return tabla; }
    public void setTabla(String tabla) { this.tabla = tabla; }

    public Integer getIdRegistro() { return idRegistro; }
    public void setIdRegistro(Integer idRegistro) { this.idRegistro = idRegistro; }

    public String getAccion() { return accion; }
    public void setAccion(String accion) { this.accion = accion; }

    public Integer getIdUsuario() { return idUsuario; }
    public void setIdUsuario(Integer idUsuario) { this.idUsuario = idUsuario; }

    public String getUsuarioNombre() { return usuarioNombre; }
    public void setUsuarioNombre(String usuarioNombre) { this.usuarioNombre = usuarioNombre; }

    public String getValorAnterior() { return valorAnterior; }
    public void setValorAnterior(String valorAnterior) { this.valorAnterior = valorAnterior; }

    public String getValorNuevo() { return valorNuevo; }
    public void setValorNuevo(String valorNuevo) { this.valorNuevo = valorNuevo; }

    public String getDetalle() { return detalle; }
    public void setDetalle(String detalle) { this.detalle = detalle; }

    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
}
