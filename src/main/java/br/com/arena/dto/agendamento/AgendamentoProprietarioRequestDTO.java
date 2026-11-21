package br.com.arena.dto.agendamento;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Campo de reatribuição aceito somente pela operação administrativa explícita. */
@Data
public class AgendamentoProprietarioRequestDTO {

    @NotNull
    private Long usuarioId;
}
