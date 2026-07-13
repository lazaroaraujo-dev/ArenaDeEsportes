package br.com.arena.dto.agendamento;

import br.com.arena.enumerate.StatusAgendamento;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class AgendamentoResponseDTO {

    private Long id;

    private LocalDate data;

    private LocalTime horaInicio;

    private LocalTime horaFim;

    private StatusAgendamento status;

    private String nomeUsuario;

    private String nomeQuadra;
}