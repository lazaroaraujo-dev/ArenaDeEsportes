package br.com.arena.mapper;

import br.com.arena.dto.agendamento.AgendamentoRequestDTO;
import br.com.arena.dto.agendamento.AgendamentoResponseDTO;
import br.com.arena.model.Agendamento;

public class AgendamentoMapper {

    public static Agendamento toEntity(AgendamentoRequestDTO dto) {

        Agendamento agendamento = new Agendamento();

        agendamento.setData(dto.getData());
        agendamento.setHoraInicio(dto.getHoraInicio());
        agendamento.setHoraFim(dto.getHoraFim());

        return agendamento;
    }

    public static AgendamentoResponseDTO toDTO(Agendamento agendamento) {

        AgendamentoResponseDTO dto = new AgendamentoResponseDTO();

        dto.setId(agendamento.getId());
        dto.setData(agendamento.getData());
        dto.setHoraInicio(agendamento.getHoraInicio());
        dto.setHoraFim(agendamento.getHoraFim());
        dto.setStatus(agendamento.getStatus());

        dto.setNomeUsuario(agendamento.getUsuario().getNome());
        dto.setNomeQuadra(agendamento.getQuadra().getNome());

        return dto;
    }

}