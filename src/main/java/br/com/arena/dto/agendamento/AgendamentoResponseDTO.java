package br.com.arena.dto.agendamento;

import br.com.arena.enumerate.StatusAgendamento;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
public class AgendamentoResponseDTO {

    private Long id;

    private LocalDate data;

    private LocalTime horaInicio;

    private LocalTime horaFim;

    private StatusAgendamento status;

    @Schema(
            accessMode = Schema.AccessMode.READ_ONLY,
            description = "Instante de criação gerado pelo PostgreSQL; define o prazo original de 12 horas."
    )
    private Instant criadoEm;

    private String nomeUsuario;

    private String nomeQuadra;
}
