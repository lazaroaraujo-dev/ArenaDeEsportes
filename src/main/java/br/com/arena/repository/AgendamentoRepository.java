package br.com.arena.repository;

import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface AgendamentoRepository extends JpaRepository<Agendamento, Long> {
    List<Agendamento> findByQuadraAndData(
            Quadra quadra,
            LocalDate data
    );
}