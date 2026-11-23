package com.lastulas.ica;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Integer> {

    Optional<Usuario> findByUsuarioLoginIgnoreCase(String usuarioLogin);

    List<Usuario> findAllByOrderByIdUsuarioAsc();

    @Query("SELECT u FROM Usuario u WHERE UPPER(u.rol.nombre) = UPPER(:rol) AND UPPER(u.estado) = 'ACTIVO' ORDER BY u.idUsuario ASC")
    List<Usuario> findActivosPorRol(@Param("rol") String rol);

    @Query("SELECT u FROM Usuario u WHERE UPPER(u.rol.nombre) = 'CAJERO' AND u.estado = 'Activo' ORDER BY u.idUsuario ASC")
    List<Usuario> findCajerosActivos();
}
