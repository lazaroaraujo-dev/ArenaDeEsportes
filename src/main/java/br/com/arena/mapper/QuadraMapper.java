package br.com.arena.mapper;

import br.com.arena.dto.quadra.QuadraRequestDTO;
import br.com.arena.dto.quadra.QuadraResponseDTO;
import br.com.arena.model.Quadra;

public class QuadraMapper {

    public static Quadra toEntity(QuadraRequestDTO dto) {

        Quadra quadra = new Quadra();

        quadra.setNome(dto.getNome());
        quadra.setTipo(dto.getTipo());
        quadra.setAtiva(dto.isAtiva());

        return quadra;
    }

    public static QuadraResponseDTO toDTO(Quadra quadra) {

        QuadraResponseDTO dto = new QuadraResponseDTO();

        dto.setId(quadra.getId());
        dto.setNome(quadra.getNome());
        dto.setTipo(quadra.getTipo());
        dto.setAtiva(quadra.isAtiva());

        return dto;
    }

}