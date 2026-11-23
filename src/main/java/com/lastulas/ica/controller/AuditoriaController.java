package com.lastulas.ica.controller;

import com.lastulas.ica.Auditoria;
import com.lastulas.ica.AuditoriaRepository;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto 12: bitacora de cambios administrativos.
 *
 * Solo ADMIN. La anulacion de una venta SI dejaba constancia antes, pero en la
 * tabla notas de credito, sin valor anterior y sin las demas acciones. Aqui se
 * ve que cambio, quien lo cambio y como estaba antes.
 *
 * La lista se pagina porque la bitacora crece con cada operacion: devolverla
 * entera seria el mismo problema que se esta resolviendo en los reportes.
 */
@RestController
@RequestMapping("/api/auditoria")
@CrossOrigin(originPatterns = "*")
public class AuditoriaController {

    private static final int TAMANO_MAXIMO = 200;

    private final AuditoriaRepository repo;
    private final UsuarioRepository usuarioRepo;

    public AuditoriaController(AuditoriaRepository repo, UsuarioRepository usuarioRepo) {
        this.repo = repo;
        this.usuarioRepo = usuarioRepo;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> listar(@RequestParam(defaultValue = "0") int pagina,
                                   @RequestParam(defaultValue = "10") int tamano,
                                   @RequestParam(required = false) String tabla,
                                   @RequestParam(required = false) String accion,
                                   @RequestParam(required = false) LocalDate desde,
                                   @RequestParam(required = false) LocalDate hasta) {

        Pageable pageable = PageRequest.of(Math.max(0, pagina),
                Math.min(Math.max(1, tamano), TAMANO_MAXIMO),
                Sort.by(Sort.Direction.DESC, "fecha"));

        // Todos los filtros van en un unico Specification en vez de uno por
        // parametro: asi la bitacora se puede acotar por periodo y accion sin
        // tener que traerla entera y filtrar en el navegador.
        var resultado = repo.findAll(spec(tabla, accion, desde, hasta), pageable);

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("contenido", aFilas(resultado.getContent()));
        respuesta.put("pagina", resultado.getNumber());
        respuesta.put("tamano", resultado.getSize());
        respuesta.put("totalPaginas", resultado.getTotalPages());
        respuesta.put("totalElementos", resultado.getTotalElements());
        respuesta.put("primera", resultado.isFirst());
        respuesta.put("ultima", resultado.isLast());
        return ResponseEntity.ok(respuesta);
    }

    /** Historial de un registro concreto: "que le pasó a esta venta". */
    @GetMapping("/registro")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deRegistro(@RequestParam String tabla, @RequestParam Integer idRegistro) {
        List<Auditoria> filas = repo.findByTablaAndIdRegistroOrderByFechaDesc(
                tabla != null ? tabla.trim() : "", idRegistro);
        return ResponseEntity.ok(aFilas(filas));
    }

    private static Specification<Auditoria> spec(String tabla, String accion,
                                                LocalDate desde, LocalDate hasta) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();

            if (tabla != null && !tabla.isBlank()) {
                ps.add(cb.equal(root.get("tabla"), tabla.trim()));
            }
            if (accion != null && !accion.isBlank()) {
                ps.add(cb.equal(root.get("accion"), accion.trim()));
            }
            // [desde 00:00, hasta+1 00:00): incluir el dia completo del "hasta"
            // sin perder lo que se registro despues de medianoche.
            if (desde != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("fecha"), desde.atStartOfDay()));
            }
            if (hasta != null) {
                ps.add(cb.lessThan(root.get("fecha"), hasta.plusDays(1).atStartOfDay()));
            }

            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private List<Map<String, Object>> aFilas(List<Auditoria> registros) {
        // Un solo passage por usuarios para los nombres: un usuarioRepo por
        // fila de bitacora seria N+1 otra vez.
        Map<Integer, String> nombres = new HashMap<>();
        for (Usuario u : usuarioRepo.findAllByOrderByIdUsuarioAsc()) {
            nombres.put(u.getIdUsuario(), u.getNombreCompleto());
        }

        List<Map<String, Object>> filas = new ArrayList<>(registros.size());
        for (Auditoria a : registros) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idAuditoria", a.getIdAuditoria());
            m.put("tabla", a.getTabla());
            m.put("idRegistro", a.getIdRegistro());
            m.put("accion", a.getAccion());
            m.put("idUsuario", a.getIdUsuario());
            // Se prefiere el nombre congelado en la fila: si el usuario se
            // renombra despues, el historial tiene que seguir diciendo como
            // se llamaba cuando ocurrio.
            m.put("usuario", a.getUsuarioNombre() != null
                    ? a.getUsuarioNombre() : nombres.getOrDefault(a.getIdUsuario(), "Sistema"));
            m.put("valorAnterior", a.getValorAnterior());
            m.put("valorNuevo", a.getValorNuevo());
            m.put("detalle", a.getDetalle());
            m.put("fecha", a.getFecha() != null ? a.getFecha().toString() : null);
            filas.add(m);
        }
        return filas;
    }
}