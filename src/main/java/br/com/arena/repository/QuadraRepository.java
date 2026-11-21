package br.com.arena.repository;

import br.com.arena.model.Quadra;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuadraRepository extends JpaRepository<Quadra, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM Quadra q WHERE q.id = :id")
    Optional<Quadra> buscarPorIdParaAtualizacao(@Param("id") Long id);
}
