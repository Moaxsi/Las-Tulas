package com.lastulas.ica.controller;

import com.lastulas.ica.Rol;
import com.lastulas.ica.RolRepository;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import com.lastulas.ica.Auditoria;
import com.lastulas.ica.security.UsuarioPrincipal;
import com.lastulas.ica.service.AuditoriaService;
import com.lastulas.ica.service.BusinessException;
import com.lastulas.ica.service.CajaOperacionLock;
import com.lastulas.ica.AuditoriaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gestion de cajeros. Solo ADMIN. El PIN se guarda hasheado con BCrypt a
 * partir de ahora (punto 9); el campo en plano queda vacio.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns = "*")
public class UsuarioController {

    private final UsuarioRepository usuarioRepo;
    private final RolRepository rolRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuditoriaService auditoria;
    private final AuditoriaRepository auditoriaRepo;
    private final EntityManager em;
    private final CajaOperacionLock cajaLock;

    public UsuarioController(UsuarioRepository usuarioRepo,
                             RolRepository rolRepo,
                             PasswordEncoder passwordEncoder,
                             AuditoriaService auditoria, AuditoriaRepository auditoriaRepo,
                             EntityManager em, CajaOperacionLock cajaLock) {
        this.usuarioRepo = usuarioRepo;
        this.rolRepo = rolRepo;
        this.passwordEncoder = passwordEncoder;
        this.auditoria = auditoria;
        this.auditoriaRepo = auditoriaRepo;
        this.em = em;
        this.cajaLock = cajaLock;
    }

    @GetMapping("/usuarios")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listarUsuarios() {
        List<Map<String, Object>> respuesta = new ArrayList<>();

        for (Usuario u : usuarioRepo.findAllByOrderByIdUsuarioAsc()) {
            String nombreRol = (u.getRol() != null && u.getRol().getNombre() != null)
                    ? u.getRol().getNombre()
                    : "CAJERO";

            if ("CAJERO".equalsIgnoreCase(nombreRol)) {
                Map<String, Object> map = new HashMap<>();
                map.put("idUsuario", u.getIdUsuario());
                map.put("nombreCompleto", u.getNombreCompleto());
                map.put("usuarioLogin", u.getUsuarioLogin());
                map.put("rol", "Cajero");
                map.put("estado", u.getEstado() != null ? u.getEstado() : "Activo");
                map.put("fechaCreacion", u.getFechaCreacion() != null
                        ? u.getFechaCreacion().toString() : LocalDateTime.now().toString());
                respuesta.add(map);
            }
        }
        return respuesta;
    }

    @PostMapping("/usuarios")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> crearUsuarioCajero(@RequestBody Map<String, String> body) {
        String nombreCompleto = body.get("nombreCompleto");
        String login = body.get("usuarioLogin");
        String pin = body.get("password");

        if (nombreCompleto == null || nombreCompleto.trim().isEmpty()
            || login == null || login.trim().isEmpty()
            || pin == null || pin.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "Todos los campos son obligatorios."));
        }

        if (!pin.trim().matches("\\d{4,10}")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "El PIN debe tener entre 4 y 10 digitos."));
        }

        if (usuarioRepo.findByUsuarioLoginIgnoreCase(login.trim()).isPresent()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "El usuario '" + login.trim() + "' ya existe."));
        }

        Rol rolCajero = rolRepo.findByNombreIgnoreCase("CAJERO")
                .orElseGet(() -> rolRepo.findById(2)
                        .orElseGet(() -> rolRepo.save(new Rol(2, "CAJERO"))));

        Usuario nuevo = new Usuario();
        nuevo.setNombreCompleto(nombreCompleto.trim());
        nuevo.setUsuarioLogin(login.trim().toLowerCase(Locale.ROOT));
        nuevo.setCodigoPin("");                    // ya no se guarda el PIN en plano
        nuevo.setPinHash(passwordEncoder.encode(pin.trim()));
        nuevo.setRol(rolCajero);
        nuevo.setEstado("Activo");
        nuevo.setFechaCreacion(LocalDateTime.now());

        Usuario guardado = usuarioRepo.save(nuevo);

        Map<String, Object> respuesta = new HashMap<>();
        respuesta.put("success", true);
        Map<String, Object> usuario = new HashMap<>();
        usuario.put("idUsuario", guardado.getIdUsuario());
        usuario.put("nombreCompleto", guardado.getNombreCompleto());
        usuario.put("usuarioLogin", guardado.getUsuarioLogin());
        usuario.put("rol", "Cajero");
        usuario.put("estado", guardado.getEstado());
        respuesta.put("usuario", usuario);
        return ResponseEntity.ok(respuesta);
    }

    @PutMapping("/usuarios/mi-pin")
    @PreAuthorize("hasAnyRole('ADMIN', 'CAJERO')")
    public ResponseEntity<?> cambiarMiPin(@RequestBody Map<String, String> body,
                                          @AuthenticationPrincipal UsuarioPrincipal principal) {
        String pinActual = body.get("pinActual");
        String pinNuevo = body.get("pinNuevo");
        if (pinActual == null || pinActual.isBlank()
                || pinNuevo == null || !pinNuevo.matches("\\d{4,10}")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Indica tu PIN actual y un PIN nuevo de 4 a 10 digitos."));
        }

        Usuario usuario = usuarioRepo.findById(principal.getIdUsuario())
                .orElseThrow(() -> BusinessException.noEncontrado("El usuario"));
        boolean pinActualValido = usuario.esLegacyPin()
                ? usuario.getCodigoPin() != null && usuario.getCodigoPin().equals(pinActual)
                : passwordEncoder.matches(pinActual, usuario.getPinHash());
        if (!pinActualValido) {
            return ResponseEntity.status(400).body(Map.of(
                    "success", false, "message", "El PIN actual es incorrecto."));
        }

        usuario.setPinHash(passwordEncoder.encode(pinNuevo));
        usuario.setCodigoPin("");
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);
        usuarioRepo.save(usuario);
        auditoria.registrar(Auditoria.TABLA_USUARIOS, usuario.getIdUsuario(), "MODIFICAR",
                "PIN anterior protegido", "PIN actualizado",
                principal.getNombreCompleto() + " actualizo su PIN", principal);

        return ResponseEntity.ok(Map.of("success", true, "message", "Tu PIN se actualizo correctamente."));
    }

    @PutMapping("/usuarios/{id}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> cambiarEstado(@PathVariable Integer id, @RequestBody Map<String, String> body,
                                           @AuthenticationPrincipal UsuarioPrincipal principal) {
        Usuario usuario = cajero(id);
        String estado = body.get("estado");
        if (!"Activo".equals(estado) && !"Inactivo".equals(estado)) {
            throw new BusinessException("El estado debe ser Activo o Inactivo.");
        }
        String anterior = usuario.getEstado();
        usuario.setEstado(estado);
        usuarioRepo.save(usuario);
        auditoria.registrar(Auditoria.TABLA_USUARIOS, id, "Activo".equals(estado) ? "REACTIVAR" : "DAR_DE_BAJA",
                anterior, estado, "Cambio de acceso del cajero " + usuario.getNombreCompleto(), principal);
        return ResponseEntity.ok(Map.of("success", true, "message", "Estado actualizado correctamente."));
    }

    @DeleteMapping("/usuarios/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ResponseEntity<?> eliminarUsuario(@PathVariable Integer id,
                                             @AuthenticationPrincipal UsuarioPrincipal principal) {
        cajaLock.adquirir();
        Usuario usuario = cajero(id);
        // Ventas y auditoría guardan el identificador histórico sin FK. Los
        // arqueos antiguos sí tienen FK: se transfiere su custodia al admin,
        // sin modificar montos, fechas, estado ni ventas de esos arqueos.
        for (Object fila : em.createNativeQuery("SELECT id_caja_turno FROM cajas_turno WHERE id_usuario = :id")
                .setParameter("id", id).getResultList()) {
            Auditoria traspaso = new Auditoria();
            traspaso.setTabla(Auditoria.TABLA_CAJAS);
            traspaso.setIdRegistro(((Number) fila).intValue());
            traspaso.setAccion("TRANSFERIR_CUSTODIA");
            traspaso.setValorAnterior(usuario.getNombreCompleto());
            traspaso.setValorNuevo(principal.getNombreCompleto());
            traspaso.setDetalle("Custodia de arqueo histórico tras eliminar al cajero #" + id);
            traspaso.setIdUsuario(principal.getIdUsuario());
            traspaso.setUsuarioNombre(principal.getNombreCompleto());
            auditoriaRepo.save(traspaso);
        }
        em.createNativeQuery("UPDATE cajas_turno SET id_usuario = :admin WHERE id_usuario = :id")
                .setParameter("admin", principal.getIdUsuario()).setParameter("id", id).executeUpdate();
        // Las anulaciones conservan su autor original en auditoría.
        em.createNativeQuery("UPDATE ventas SET id_usuario_anulacion = NULL WHERE id_usuario_anulacion = :id")
                .setParameter("id", id).executeUpdate();
        em.createNativeQuery("UPDATE notas_credito SET id_usuario_anulacion = NULL WHERE id_usuario_anulacion = :id")
                .setParameter("id", id).executeUpdate();
        Auditoria respaldo = new Auditoria();
        respaldo.setTabla(Auditoria.TABLA_USUARIOS);
        respaldo.setIdRegistro(id);
        respaldo.setAccion("ELIMINAR");
        respaldo.setValorAnterior(usuario.getNombreCompleto());
        respaldo.setValorNuevo("Cuenta eliminada permanentemente; ventas e historial conservados");
        respaldo.setDetalle("Eliminación de la cuenta " + usuario.getUsuarioLogin());
        respaldo.setIdUsuario(principal.getIdUsuario());
        respaldo.setUsuarioNombre(principal.getNombreCompleto());
        // Este respaldo es obligatorio y comparte transacción con el DELETE.
        auditoriaRepo.saveAndFlush(respaldo);
        usuarioRepo.delete(usuario);
        usuarioRepo.flush();
        return ResponseEntity.ok(Map.of("success", true, "message", "Cajero eliminado. Sus ventas e historial se conservan."));
    }

    private Usuario cajero(Integer id) {
        Usuario usuario = usuarioRepo.findById(id).orElseThrow(() -> BusinessException.noEncontrado("El cajero"));
        if (usuario.getRol() == null || !"CAJERO".equalsIgnoreCase(usuario.getRol().getNombre())) {
            throw new BusinessException("Esta acción solo está disponible para cuentas de cajero.");
        }
        return usuario;
    }
}
