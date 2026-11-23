package com.lastulas.ica.security;

import com.lastulas.ica.UsuarioRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lee el token JWT de la cabecera Authorization y arma la identidad de la
 * peticion. Sin token valido la peticion sigue sin autenticar y la cadena de
 * filtros responde 401 antes de tocar el controlador (punto 9).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final UsuarioRepository usuarios;

    public JwtAuthFilter(JwtDecoder jwtDecoder, UsuarioRepository usuarios) {
        this.jwtDecoder = jwtDecoder;
        this.usuarios = usuarios;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolverToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Jwt jwt = jwtDecoder.decode(token);
                var usuario = usuarios.findById(Integer.valueOf(jwt.getSubject()))
                        .filter(u -> "Activo".equalsIgnoreCase(u.getEstado()))
                        .orElseThrow(() -> new IllegalArgumentException("Cuenta inactiva o eliminada"));
                UsuarioPrincipal principal = UsuarioPrincipal.desde(usuario);

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                org.springframework.security.core.context.SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);

            } catch (JwtException | IllegalArgumentException e) {
                // Token vencido, adulterado o mal formado. No es un error 500:
                // se deja sin autenticar y el entry point responde 401.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolverToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            String valor = header.substring(PREFIX.length()).trim();
            return valor.isEmpty() ? null : valor;
        }
        return null;
    }
}
