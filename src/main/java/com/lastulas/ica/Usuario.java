package com.lastulas.ica;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_usuario")
    private Integer idUsuario;

    @Column(name = "nombre_completo", nullable = false, length = 120)
    private String nombreCompleto;

    @Column(name = "usuario_login", nullable = false, unique = true, length = 50)
    private String usuarioLogin;

    /**
     * PIN en texto plano. Solo se conserva para los usuarios creados
     * antes del cambio a BCrypt; no debe usarse para validar nunca que
     * {@link #pinHash} este informado.
     */
    @Column(name = "codigo_pin", nullable = false, length = 10)
    private String codigoPin;

    /**
     * PIN hasheado con BCrypt (punto 9). Es NULL en los usuarios legacy: en su
     * primer login exitoso el backend hashea el PIN plano y lo guarda aqui,
     * asi que la transicion es transparente y nadie tiene que reescribir su PIN.
     */
    @Column(name = "pin_hash", length = 100)
    private String pinHash;

    @ManyToOne
    @JoinColumn(name = "id_rol", nullable = false)
    private Rol rol;

    @Column(name = "estado", length = 20)
    private String estado = "Activo";

    @Column(name = "fecha_creacion")
    private LocalDateTime fechaCreacion;

    /**
     * Contador de intentos fallidos de login consecutivos.
     * Se reinicia a 0 tras un login exitoso.
     */
    @Column(name = "intentos_fallidos", nullable = false)
    private Integer intentosFallidos = 0;

    /**
     * Fecha/hora hasta la que el usuario permanece bloqueado por exceso
     * de intentos fallidos. Null si no está bloqueado.
     */
    @Column(name = "bloqueado_hasta")
    private LocalDateTime bloqueadoHasta;

    @PrePersist
    public void prePersist() {
        if (this.fechaCreacion == null) {
            this.fechaCreacion = LocalDateTime.now();
        }
        if (this.estado == null) {
            this.estado = "Activo";
        }
    }

    public Usuario() {}

    public Integer getIdUsuario() { return idUsuario; }
    public void setIdUsuario(Integer idUsuario) { this.idUsuario = idUsuario; }

    public String getNombreCompleto() { return nombreCompleto; }
    public void setNombreCompleto(String nombreCompleto) { this.nombreCompleto = nombreCompleto; }

    public String getUsuarioLogin() { return usuarioLogin; }
    public void setUsuarioLogin(String usuarioLogin) { this.usuarioLogin = usuarioLogin; }

    public String getCodigoPin() { return codigoPin; }
    public void setCodigoPin(String codigoPin) { this.codigoPin = codigoPin; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    /** true si todavia no tiene PIN hasheado y hay que migrarlo en el próximo login. */
    public boolean esLegacyPin() { return pinHash == null || pinHash.isBlank(); }

    public Rol getRol() { return rol; }
    public void setRol(Rol rol) { this.rol = rol; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public LocalDateTime getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(LocalDateTime fechaCreacion) { this.fechaCreacion = fechaCreacion; }

    public Integer getIntentosFallidos() { return intentosFallidos; }
    public void setIntentosFallidos(Integer intentosFallidos) { this.intentosFallidos = intentosFallidos; }

    public LocalDateTime getBloqueadoHasta() { return bloqueadoHasta; }
    public void setBloqueadoHasta(LocalDateTime bloqueadoHasta) { this.bloqueadoHasta = bloqueadoHasta; }

    /** true si el usuario está bloqueado en este momento. */
    public boolean estaBloqueado() {
        return bloqueadoHasta != null && bloqueadoHasta.isAfter(LocalDateTime.now());
    }
}