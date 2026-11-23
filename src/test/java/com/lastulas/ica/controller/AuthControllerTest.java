package com.lastulas.ica.controller;

import com.lastulas.ica.Rol;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import com.lastulas.ica.config.RateLimitConfig;
import com.lastulas.ica.security.JwtService;
import com.lastulas.ica.service.IgvService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private UsuarioRepository usuarioRepo;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        usuarioRepo = mock(UsuarioRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        controller = new AuthController(usuarioRepo, passwordEncoder, jwtService,
                mock(IgvService.class), new RateLimitConfig());
    }

    @Test
    void unPinIncorrectoNoBloqueaCuentasQueNoSePuedenIdentificarSinElPin() {
        Usuario cajero = usuario("luis", "CAJERO");
        cajero.setPinHash("hash-luis");
        Usuario otraCuenta = usuario("ana", "CAJERO");
        otraCuenta.setPinHash("hash-ana");
        when(usuarioRepo.findActivosPorRol("CAJERO")).thenReturn(List.of(cajero, otraCuenta));
        when(passwordEncoder.matches("9999", "hash-luis")).thenReturn(false);
        when(passwordEncoder.matches("9999", "hash-ana")).thenReturn(false);

        ResponseEntity<?> response = controller.login(
                Map.of("rol", "CAJERO", "pin", "9999"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(cajero.getIntentosFallidos()).isZero();
        assertThat(otraCuenta.getIntentosFallidos()).isZero();
        verify(usuarioRepo, never()).save(same(cajero));
        verify(usuarioRepo, never()).save(same(otraCuenta));
    }

    @Test
    void loginLegacyMigraElPinYEliminaElValorEnTextoPlano() {
        Usuario administrador = usuario("admin", "ADMIN");
        administrador.setCodigoPin("1234");
        administrador.setIntentosFallidos(2);
        when(usuarioRepo.findActivosPorRol("ADMIN")).thenReturn(List.of(administrador));
        when(passwordEncoder.encode("1234")).thenReturn("hash-protegido");
        when(jwtService.generarToken(org.mockito.ArgumentMatchers.any())).thenReturn("jwt");

        ResponseEntity<?> response = controller.login(
                Map.of("rol", "ADMINISTRADOR", "pin", "1234"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(administrador.getPinHash()).isEqualTo("hash-protegido");
        assertThat(administrador.getCodigoPin()).isEmpty();
        assertThat(administrador.getIntentosFallidos()).isZero();
        verify(usuarioRepo).save(same(administrador));
    }

    @Test
    void rechazaElPinCuandoEstaCompartidoPorDosCuentasDelMismoRol() {
        Usuario cajero = usuario("luis", "CAJERO");
        cajero.setCodigoPin("1234");
        Usuario otraCuenta = usuario("ana", "CAJERO");
        otraCuenta.setCodigoPin("1234");
        when(usuarioRepo.findActivosPorRol("CAJERO")).thenReturn(List.of(cajero, otraCuenta));

        ResponseEntity<?> response = controller.login(
                Map.of("rol", "CAJERO", "pin", "1234"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(usuarioRepo, never()).save(org.mockito.ArgumentMatchers.any(Usuario.class));
    }

    @Test
    void elLimiteDeSolicitudesSeAplicaPorIpDeConexion() {
        Map<String, String> credenciales = Map.of("rol", "CAJERO", "pin", "1234");
        for (int intento = 0; intento < 5; intento++) {
            assertThat(controller.login(credenciales, request).getStatusCode().value()).isEqualTo(401);
        }
        assertThat(controller.login(credenciales, request).getStatusCode().value()).isEqualTo(429);

        HttpServletRequest otraIp = mock(HttpServletRequest.class);
        when(otraIp.getRemoteAddr()).thenReturn("192.0.2.11");
        assertThat(controller.login(credenciales, otraIp).getStatusCode().value()).isEqualTo(401);
    }

    private static Usuario usuario(String login, String rol) {
        Usuario usuario = new Usuario();
        usuario.setIdUsuario(1);
        usuario.setUsuarioLogin(login);
        usuario.setNombreCompleto(login);
        usuario.setRol(new Rol(1, rol));
        usuario.setEstado("Activo");
        usuario.setIntentosFallidos(0);
        return usuario;
    }
}
