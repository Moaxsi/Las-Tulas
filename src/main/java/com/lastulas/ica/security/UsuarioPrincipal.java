package com.lastulas.ica.security;

import com.lastulas.ica.Usuario;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Identidad del usuario que quedo autenticada en la peticion.
 * Viaja como principal dentro del token JWT, por eso el backend puede saber
 * quien cobro sin que el navegador le diga (punto 9 y punto 10).
 */
public class UsuarioPrincipal implements UserDetails {

    private final Integer idUsuario;
    private final String nombreCompleto;
    private final String usuarioLogin;
    private final String rol;
    private final String tokenHashPlaceholder;

    public UsuarioPrincipal(Integer idUsuario, String nombreCompleto, String usuarioLogin, String rol) {
        this.idUsuario = idUsuario;
        this.nombreCompleto = nombreCompleto;
        this.usuarioLogin = usuarioLogin;
        this.rol = rol;
        this.tokenHashPlaceholder = null;
    }

    public static UsuarioPrincipal desde(Usuario u) {
        String nombreRol = (u.getRol() != null && u.getRol().getNombre() != null)
                ? u.getRol().getNombre()
                : "CAJERO";
        return new UsuarioPrincipal(u.getIdUsuario(), u.getNombreCompleto(), u.getUsuarioLogin(), nombreRol);
    }

    public Integer getIdUsuario() { return idUsuario; }
    public String getNombreCompleto() { return nombreCompleto; }
    public String getUsuarioLogin() { return usuarioLogin; }
    public String getRol() { return rol; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + rol));
    }

    /** No se usa: la validacion real ocurre contra BCrypt en AuthController. */
    @Override
    public String getPassword() { return tokenHashPlaceholder; }

    @Override
    public String getUsername() { return usuarioLogin; }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
