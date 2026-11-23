package com.lastulas.ica.security;

import com.lastulas.ica.Rol;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {
    @AfterEach
    void limpiarSesion() { SecurityContextHolder.clearContext(); }

    @Test
    void tokenVigenteNoPermiteOperarConCuentaDesactivadaOEliminada() throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        when(decoder.decode("token-prueba")).thenReturn(Jwt.withTokenValue("token-prueba")
                .header("alg", "HS256").subject("7").claim("rol", "CAJERO").build());
        Usuario inactivo = new Usuario(); inactivo.setEstado("Inactivo");
        when(usuarios.findById(7)).thenReturn(Optional.of(inactivo), Optional.empty());
        JwtAuthFilter filtro = new JwtAuthFilter(decoder, usuarios);
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer token-prueba");
            filtro.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void cuentaActivaSeAutenticaConSuRolActual() throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        UsuarioRepository usuarios = mock(UsuarioRepository.class);
        when(decoder.decode("token-prueba")).thenReturn(Jwt.withTokenValue("token-prueba")
                .header("alg", "HS256").subject("7").claim("rol", "ADMIN").build());
        Usuario usuario = new Usuario(); usuario.setIdUsuario(7); usuario.setEstado("Activo");
        usuario.setNombreCompleto("Cajero"); usuario.setUsuarioLogin("cajero"); usuario.setRol(new Rol(2, "CAJERO"));
        when(usuarios.findById(7)).thenReturn(Optional.of(usuario));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token-prueba");
        new JwtAuthFilter(decoder, usuarios).doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        UsuarioPrincipal principal = (UsuarioPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.getRol()).isEqualTo("CAJERO");
    }
}
