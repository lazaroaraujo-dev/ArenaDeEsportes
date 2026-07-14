package br.com.arena.service;

import br.com.arena.dto.agendamento.AgendamentoRequestDTO;
import br.com.arena.dto.agendamento.AgendamentoResponseDTO;
import br.com.arena.enumerate.StatusAgendamento;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.ResourceNotFoundException;
import br.com.arena.mapper.AgendamentoMapper;
import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import br.com.arena.model.Usuario;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.security.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AgendamentoService {

    private final AgendamentoRepository agendamentoRepository;
    private final UsuarioService usuarioService;
    private final QuadraService quadraService;

    public AgendamentoService(AgendamentoRepository agendamentoRepository,
                              UsuarioService usuarioService,
                              QuadraService quadraService) {
        this.agendamentoRepository = agendamentoRepository;
        this.usuarioService = usuarioService;
        this.quadraService = quadraService;
    }

    public List<AgendamentoResponseDTO> listarTodos() {

        return agendamentoRepository.findAll()
                .stream()
                .map(AgendamentoMapper::toDTO)
                .collect(Collectors.toList());
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

        Usuario usuarioLogado = SecurityUtils.getUsuarioLogado();

        Long usuarioIdFinal = SecurityUtils.isAdmin()
                ? dto.getUsuarioId()
                : usuarioLogado.getId();

        Usuario usuario = usuarioService.buscarEntidadePorId(usuarioIdFinal);
        Quadra quadra = quadraService.buscarEntidadePorId(dto.getQuadraId());

        if (!quadra.isAtiva()) {
            throw new BusinessException("A quadra está inativa.");
        }

        List<Agendamento> agendamentos =
                agendamentoRepository.findByQuadraAndData(
                        quadra,
                        dto.getData()
                );

        for (Agendamento a : agendamentos) {

            boolean conflito =
                    dto.getHoraInicio().isBefore(a.getHoraFim()) &&
                            dto.getHoraFim().isAfter(a.getHoraInicio());

            if (conflito) {
                throw new BusinessException(
                        "Já existe um agendamento nesse horário."
                );
            }
        }

        Agendamento agendamento = AgendamentoMapper.toEntity(dto);

        agendamento.setUsuario(usuario);
        agendamento.setQuadra(quadra);
        agendamento.setStatus(StatusAgendamento.PENDENTE);

        agendamento = agendamentoRepository.save(agendamento);

        return AgendamentoMapper.toDTO(agendamento);
    }

    public AgendamentoResponseDTO atualizar(Long id, AgendamentoRequestDTO dto) {

        Agendamento agendamento = buscarEntidadePorId(id);
        validarPermissao(agendamento);

        Usuario usuario = usuarioService.buscarEntidadePorId(dto.getUsuarioId());
        Quadra quadra = quadraService.buscarEntidadePorId(dto.getQuadraId());

        agendamento.setData(dto.getData());
        agendamento.setHoraInicio(dto.getHoraInicio());
        agendamento.setHoraFim(dto.getHoraFim());
        agendamento.setUsuario(usuario);
        agendamento.setQuadra(quadra);

        agendamento = agendamentoRepository.save(agendamento);

        return AgendamentoMapper.toDTO(agendamento);
    }

    public void deletar(Long id) {

        Agendamento agendamento = buscarEntidadePorId(id);
        validarPermissao(agendamento);

        agendamentoRepository.delete(agendamento);
    }

    private void validarPermissao(Agendamento agendamento) {

        Usuario usuarioLogado = SecurityUtils.getUsuarioLogado();

        boolean isDono = agendamento.getUsuario()
                .getId()
                .equals(usuarioLogado.getId());

        if (!isDono && !SecurityUtils.isAdmin()) {
            throw new AccessDeniedException(
                    "Você não tem permissão para acessar este agendamento."
            );
        }
    }

}