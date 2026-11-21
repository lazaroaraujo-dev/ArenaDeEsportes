package br.com.arena.repository;

import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import br.com.arena.enumerate.StatusAgendamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgendamentoRepository extends JpaRepository<Agendamento, Long> {

    List<Agendamento> findByQuadraAndData(
            Quadra quadra,
            LocalDate data
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Agendamento a WHERE a.id = :id")
    Optional<Agendamento> buscarPorIdParaAtualizacao(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a
            FROM Agendamento a
            WHERE a.quadra.id = :quadraId
              AND a.data = :data
              AND a.status IN :statusPermitidos
            ORDER BY a.id
            """)
    List<Agendamento> buscarBloqueantesPorQuadraEDataParaAtualizacao(
            @Param("quadraId") Long quadraId,
            @Param("data") LocalDate data,
            @Param("statusPermitidos") Collection<StatusAgendamento> statusPermitidos
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a
            FROM Agendamento a
            WHERE a.id = :agendamentoId
               OR (a.quadra.id = :quadraId
                   AND a.data = :data
                   AND a.status IN :statusPermitidos)
            ORDER BY a.id
            """)
    List<Agendamento> buscarAlvoEBloqueantesParaAtualizacao(
            @Param("agendamentoId") Long agendamentoId,
            @Param("quadraId") Long quadraId,
            @Param("data") LocalDate data,
            @Param("statusPermitidos") Collection<StatusAgendamento> statusPermitidos
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            WITH candidatos AS MATERIALIZED (
                SELECT id
                FROM agendamento
                WHERE status = 'PENDENTE'
                  AND criado_em <= statement_timestamp() - INTERVAL '12 hours'
                ORDER BY id
                LIMIT :tamanhoLote
                FOR UPDATE SKIP LOCKED
            )
            UPDATE agendamento AS a
            SET status = 'EXPIRADO'
            FROM candidatos AS c
            WHERE a.id = c.id
              AND a.status = 'PENDENTE'
              AND a.criado_em <= clock_timestamp() - INTERVAL '12 hours'
            """, nativeQuery = true)
    int expirarLoteVencido(@Param("tamanhoLote") int tamanhoLote);
}
