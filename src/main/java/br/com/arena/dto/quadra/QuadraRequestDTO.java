package br.com.arena.dto.quadra;

import br.com.arena.enumerate.TipoQuadra;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QuadraRequestDTO {

    @NotBlank(message = "O nome da quadra é obrigatório.")
    private String nome;

    private TipoQuadra tipo;

    private boolean ativa;
}