package br.com.arena.dto.quadra;

import br.com.arena.enumerate.TipoQuadra;
import lombok.Data;

@Data
public class QuadraResponseDTO {

    private Long id;

    private String nome;

    private TipoQuadra tipo;

    private boolean ativa;
}