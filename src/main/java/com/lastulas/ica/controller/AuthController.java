package com.lastulas.ica.controller;

import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import com.lastulas.ica.config.RateLimitConfig;
import com.lastulas.ica.security.JwtService;
import com.lastulas.ica.security.UsuarioPrincipal;
import com.lastulas.ica.service.IgvService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Punto 9: el login deja de devolver un simple "success" y empieza a
 * devolver un JWT. A partir de aqui ninguna otra llamada al backend funciona
 * sin ese token.
 *
 * Rate limiting: 5 intentos por minuto por IP (TokenBucket en memoria).
 * Al autenticar solo con PIN no se puede atribuir un PIN incorrecto a una
 * cuenta concreta, por lo que los fallos se limitan por IP.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")
public class AuthController {

    private final UsuarioRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final IgvService igvService;
    private final RateLimitConfig rateLimitConfig;

    public AuthController(UsuarioRepository usuarioRepo,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          IgvService igvService,
                          RateLimitConfig rateLimitConfig) {
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.igvService = igvService;
        this.rateLimitConfig = rateLimitConfig;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credenciales,
                                   HttpServletRequest request) {
        String ipCliente = request.getRemoteAddr();
        RateLimitConfig.TokenBucket bucket = rateLimitConfig.resolveBucket(
                ipCliente, 5, Duration.ofMinutes(1));
        if (!bucket.tryConsume(1)) {
            long segundosEspera = bucket.getNanosToWaitForRefill() / 1_000_000_000 + 1;
            return ResponseEntity.status(429)
                    .body(Map.of("success", false, "message",
                            "Demasiados intentos. Intenta de nuevo en " + segundosEspera + " segundos."));
        }

        String rolRecibido = credenciales.get("rol");
        String pinRecibido = credenciales.get("pin");

        if (rolRecibido == null || rolRecibido.isBlank()
                || pinRecibido == null || pinRecibido.isBlank()) {
            return ResponseEntity.status(400)
                    .body(Map.of("success", false, "message", "Selecciona tu rol e ingresa tu PIN."));
        }

        String rol = "ADMINISTRADOR".equalsIgnoreCase(rolRecibido.trim())
                ? "ADMIN" : rolRecibido.trim().toUpperCase();
        if (!"ADMIN".equals(rol) && !"CAJERO".equals(rol)) {
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "Rol o PIN incorrecto."));
        }

        List<Usuario> coincidencias = usuarioRepo.findActivosPorRol(rol).stream()
                .filter(usuario -> validarPin(usuario, pinRecibido))
                .toList();
        if (coincidencias.size() > 1) {
            return ResponseEntity.status(409)
                    .body(Map.of("success", false,
                            "message", "Este PIN está asignado a más de una cuenta del rol. Asigna un PIN distinto a cada persona."));
        }
        if (coincidencias.isEmpty()) {
            return ResponseEntity.status(401)
                    .body(Map.of("success", false, "message", "PIN incorrecto."));
        }

        Usuario usuario = coincidencias.get(0);
        if (usuario.estaBloqueado()) {
            long minutosRestantes = Duration.between(LocalDateTime.now(), usuario.getBloqueadoHasta()).toMinutes() + 1;
            return ResponseEntity.status(423)
                    .body(Map.of("success", false, "message",
                            "Cuenta bloqueada por intentos fallidos. Intenta en " + minutosRestantes + " minutos."));
        }

        if (usuario.esLegacyPin()) {
            usuario.setPinHash(passwordEncoder.encode(pinRecibido));
            usuario.setCodigoPin("");
        }

        UsuarioPrincipal principal = UsuarioPrincipal.desde(usuario);
        String token = jwtService.generarToken(principal);

        // Login exitoso: resetear contador de fallos
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);
        usuarioRepo.save(usuario);

        Map<String, Object> user = new HashMap<>();
        user.put("idUsuario", usuario.getIdUsuario());
        user.put("nombreCompleto", usuario.getNombreCompleto());
        user.put("usuarioLogin", usuario.getUsuarioLogin());
        user.put("rol_nombre", principal.getRol());

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("success", true);
        respuesta.put("token", token);
        respuesta.put("user", user);

        return ResponseEntity.ok(respuesta);
    }

    private boolean validarPin(Usuario usuario, String pin) {
        if (usuario.esLegacyPin()) {
            return usuario.getCodigoPin() != null && usuario.getCodigoPin().equals(pin);
        }
        return passwordEncoder.matches(pin, usuario.getPinHash());
    }

    /**
     * Configuracion que el POS necesita antes de cobrar (punto 12).
     * Publica a proposito: es la tasa de IGV, no un dato de negocio, y asi la
     * pantalla puede mostrar el desglose sin abrir sesion.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        BigDecimal tasa = igvService.tasaVigente();
        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("tasaIgv", tasa);
        respuesta.put("igvPorcentaje", tasa.multiply(BigDecimal.valueOf(100)));
        respuesta.put("nombreNegocio", "LAS TULAS");
        return respuesta;
    }
}
