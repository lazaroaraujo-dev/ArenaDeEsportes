package br.com.arena.service;

import br.com.arena.dto.agendamento.AgendamentoAtualizacaoRequestDTO;
import br.com.arena.dto.agendamento.AgendamentoRequestDTO;
import br.com.arena.dto.agendamento.AgendamentoResponseDTO;
import br.com.arena.exception.ConflictException;
import br.com.arena.exception.ResourceNotFoundException;
import br.com.arena.mapper.AgendamentoMapper;
import br.com.arena.model.Agendamento;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.security.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgendamentoService {

    private final AgendamentoRepository agendamentoRepository;
    private final AgendamentoWriteService agendamentoWriteService;

    public AgendamentoService(
            AgendamentoRepository agendamentoRepository,
            AgendamentoWriteService agendamentoWriteService) {
        this.agendamentoRepository = agendamentoRepository;
        this.agendamentoWriteService = agendamentoWriteService;
    }

    public List<AgendamentoResponseDTO> listarTodos() {
        return agendamentoRepository.findAll()
                .stream()
                .map(AgendamentoMapper::toDTO)
                .toList();
    }

    public AgendamentoResponseDTO buscarPorId(Long id) {
        Agendamento agendamento = buscarEntidadePorId(id);
        validarPermissao(agendamento);
        return AgendamentoMapper.toDTO(agendamento);
    }

    public Agendamento buscarEntidadePorId(Long id) {
        return agendamentoRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Agendamento não encontrado."));
    }

    public AgendamentoResponseDTO salvar(AgendamentoRequestDTO dto) {
        return resolver(agendamentoWriteService.salvar(dto));
    }

    public AgendamentoResponseDTO atualizar(
            Long id,
            AgendamentoAtualizacaoRequestDTO dto) {
        return resolver(agendamentoWriteService.atualizar(id, dto));
    }

    public AgendamentoResponseDTO alterarProprietarioComoAdmin(
            Long id,
            Long usuarioId) {
        return agendamentoWriteService.alterarProprietarioComoAdmin(id, usuarioId);
    }

    public AgendamentoResponseDTO confirmar(Long id) {
        return resolver(agendamentoWriteService.confirmar(id));
    }

    public AgendamentoResponseDTO cancelar(Long id) {
        return resolver(agendamentoWriteService.cancelar(id));
    }

    private <T> T resolver(AgendamentoOperationResult<T> resultado) {
        if (resultado.possuiConflito()) {
            throw new ConflictException(resultado.conflito());
        }
        return resultado.valor();
    }

    private void validarPermissao(Agendamento agendamento) {
        boolean dono = agendamento.getUsuario().getId()
                .equals(SecurityUtils.getUsuarioLogado().getId());
        if (!dono && !SecurityUtils.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Você não tem permissão para acessar este agendamento."
            );
        }
    }
}
