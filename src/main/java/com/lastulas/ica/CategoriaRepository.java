package com.lastulas.ica;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CategoriaRepository extends JpaRepository<Categoria, Integer> {

    @Query("select count(c) from Categoria c where upper(trim(c.nombre)) = :nombre")
    long contarPorNombre(@Param("nombre") String nombre);

    List<Categoria> findAllByOrderByIdCategoriaAsc();

    List<Categoria> findByEsToppingTrueOrderByIdCategoriaAsc();

    List<Categoria> findByEsToppingFalseOrderByIdCategoriaAsc();
}
