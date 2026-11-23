package com.lastulas.ica.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;

/**
 * Emision del token JWT (punto 9).
 *
 * El token lleva el id y el rol del usuario. Eso es lo que hace que el
 * servidor sepa que cajero esta cobrando sin que el navegador lo declare:
 * el id_usuario de la venta sale de aqui, no del cuerpo del POST.
 */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final SecretKey secretKey;
    private final String issuer;
    private final long expiracionHoras;

    public JwtService(JwtEncoder jwtEncoder,
                      SecretKey secretKey,
                      @Value("${app.jwt.issuer:las-tulas}") String issuer,
                      @Value("${app.jwt.expiracion-horas:12}") long expiracionHoras) {
        this.jwtEncoder = jwtEncoder;
        this.secretKey = secretKey;
        this.issuer = issuer;
        this.expiracionHoras = expiracionHoras;
    }

    public String generarToken(UsuarioPrincipal principal) {
        Instant ahora = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(ahora)
                .expiresAt(ahora.plusSeconds(expiracionHoras * 3600))
                .subject(String.valueOf(principal.getIdUsuario()))
                .claim("nombreCompleto", principal.getNombreCompleto())
                .claim("usuarioLogin", principal.getUsuarioLogin())
                .claim("rol", principal.getRol())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public SecretKey getSecretKey() { return secretKey; }

    public long getExpiracionHoras() { return expiracionHoras; }
}
