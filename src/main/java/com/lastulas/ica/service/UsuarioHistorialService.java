package com.lastulas.ica.service;

import com.lastulas.ica.Auditoria;
import com.lastulas.ica.AuditoriaRepository;
import com.lastulas.ica.Usuario;
import com.lastulas.ica.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Recupera la identidad histórica sin conservar cuentas de acceso eliminadas. */
@Service
@Transactional(readOnly = true)
public class UsuarioHistorialService {
    private final UsuarioRepository usuarios;
    private final AuditoriaRepository auditoria;

    public UsuarioHistorialService(UsuarioRepository usuarios, AuditoriaRepository auditoria) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
    }

    public Map<Integer, Usuario> porId() {
        Map<Integer, Usuario> resultado = new LinkedHashMap<>();
        usuarios.findAllByOrderByIdUsuarioAsc().forEach(u -> resultado.put(u.getIdUsuario(), u));
        for (Auditoria a : auditoria.findByTablaAndAccion(Auditoria.TABLA_USUARIOS, "ELIMINAR")) {
            Usuario eliminado = new Usuario();
            eliminado.setIdUsuario(a.getIdRegistro());
            eliminado.setNombreCompleto(a.getValorAnterior());
            eliminado.setEstado("Eliminado");
            resultado.putIfAbsent(a.getIdRegistro(), eliminado);
        }
        return resultado;
    }

    public String nombre(Integer id) {
        if (id == null) return "Sin asignar";
        return usuarios.findById(id).map(Usuario::getNombreCompleto).orElseGet(() ->
                auditoria.findByTablaAndIdRegistroOrderByFechaDesc(Auditoria.TABLA_USUARIOS, id).stream()
                        .filter(a -> "ELIMINAR".equals(a.getAccion()))
                        .map(Auditoria::getValorAnterior).findFirst().orElse("Cajero #" + id));
    }
}
