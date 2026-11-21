package br.com.arena.dto.agendamento;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** Dados editáveis de uma reserva; o proprietário não faz parte deste contrato. */
@Data
public class AgendamentoAtualizacaoRequestDTO {

    @NotNull
    private LocalDate data;

    @NotNull
    private LocalTime horaInicio;

    @NotNull
    private LocalTime horaFim;

    @NotNull
    private Long quadraId;
}
