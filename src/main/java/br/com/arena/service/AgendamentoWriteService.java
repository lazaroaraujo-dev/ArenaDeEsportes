package br.com.arena.service;

import br.com.arena.dto.agendamento.AgendamentoAtualizacaoRequestDTO;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class AgendamentoWriteService {

    private static final Collection<StatusAgendamento> STATUS_BLOQUEADORES =
            Set.of(StatusAgendamento.PENDENTE, StatusAgendamento.CONFIRMADO);
    private static final long PRAZO_EXPIRACAO_HORAS = 12;

    private final AgendamentoRepository agendamentoRepository;
    private final UsuarioService usuarioService;
    private final QuadraService quadraService;
    private final PostgresClock postgresClock;

    public AgendamentoWriteService(
            AgendamentoRepository agendamentoRepository,
            UsuarioService usuarioService,
            QuadraService quadraService,
            PostgresClock postgresClock) {
        this.agendamentoRepository = agendamentoRepository;
        this.usuarioService = usuarioService;
        this.quadraService = quadraService;
        this.postgresClock = postgresClock;
    }

    @Transactional
    public AgendamentoOperationResult<AgendamentoResponseDTO> salvar(
            AgendamentoRequestDTO dto) {
        validarHorarios(dto.getHoraInicio(), dto.getHoraFim());
        validarDataFutura(dto.getData(), dto.getHoraInicio());

        Usuario usuarioLogado = SecurityUtils.getUsuarioLogado();
        boolean admin = SecurityUtils.isAdmin();
        Long usuarioIdFinal = admin ? dto.getUsuarioId() : usuarioLogado.getId();
        Usuario usuario = usuarioService.buscarEntidadePorId(usuarioIdFinal);
        Quadra quadra = quadraService.buscarParaAtualizacao(dto.getQuadraId());
        validarQuadraAtiva(quadra);

        List<Agendamento> bloqueantes =
                agendamentoRepository.buscarBloqueantesPorQuadraEDataParaAtualizacao(
                        quadra.getId(),
                        dto.getData(),
                        STATUS_BLOQUEADORES
                );
        Instant agoraNoBanco = postgresClock.agora();
        int expirados = expirarPendentesVencidos(bloqueantes, agoraNoBanco);
        if (expirados > 0) {
            agendamentoRepository.flush();
        }

        List<Agendamento> doDia =
                agendamentoRepository.findByQuadraAndData(quadra, dto.getData());
        if (existeConflito(doDia, dto.getHoraInicio(), dto.getHoraFim(), null)) {
            return AgendamentoOperationResult.conflito(
                    "Já existe um agendamento nesse horário."
            );
        }

        Agendamento agendamento = AgendamentoMapper.toEntity(dto);
        agendamento.setUsuario(usuario);
        agendamento.setQuadra(quadra);
        agendamento.setStatus(StatusAgendamento.PENDENTE);
        agendamento = agendamentoRepository.saveAndFlush(agendamento);

        return AgendamentoOperationResult.sucesso(AgendamentoMapper.toDTO(agendamento));
    }

    @Transactional
    public AgendamentoOperationResult<AgendamentoResponseDTO> atualizar(
            Long id,
            AgendamentoAtualizacaoRequestDTO dto) {
        validarHorarios(dto.getHoraInicio(), dto.getHoraFim());
        validarDataFutura(dto.getData(), dto.getHoraInicio());

        Quadra quadraDestino = quadraService.buscarParaAtualizacao(dto.getQuadraId());
        validarQuadraAtiva(quadraDestino);

        List<Agendamento> bloqueados =
                agendamentoRepository.buscarAlvoEBloqueantesParaAtualizacao(
                        id,
                        dto.getQuadraId(),
                        dto.getData(),
                        STATUS_BLOQUEADORES
                );
        Agendamento agendamento = bloqueados.stream()
                .filter(a -> Objects.equals(a.getId(), id))
                .findFirst()
                .orElseThrow(() ->
                        new ResourceNotFoundException("Agendamento não encontrado."));

        validarPermissao(agendamento);
        Instant agoraNoBanco = postgresClock.agora();

        if (agendamento.getStatus() == StatusAgendamento.PENDENTE
                && estaVencido(agendamento, agoraNoBanco)) {
            agendamento.setStatus(StatusAgendamento.EXPIRADO);
            agendamentoRepository.flush();
            return AgendamentoOperationResult.conflito(
                    "O prazo do agendamento expirou; ele não pode ser reagendado."
            );
        }
        if (agendamento.getStatus() == StatusAgendamento.EXPIRADO
                || agendamento.getStatus() == StatusAgendamento.CANCELADO) {
            return AgendamentoOperationResult.conflito(
                    "Agendamentos CANCELADOS ou EXPIRADOS não podem ser reagendados."
            );
        }

        String conflitoDeEstado = validarEstadoEPermissaoParaReagendar(agendamento);
        if (conflitoDeEstado != null) {
            return AgendamentoOperationResult.conflito(conflitoDeEstado);
        }

        if (expirarPendentesVencidos(bloqueados, agoraNoBanco) > 0) {
            agendamentoRepository.flush();
        }

        List<Agendamento> doDia =
                agendamentoRepository.findByQuadraAndData(quadraDestino, dto.getData());
        if (existeConflito(
                doDia,
                dto.getHoraInicio(),
                dto.getHoraFim(),
                agendamento.getId())) {
            return AgendamentoOperationResult.conflito(
                    "Já existe um agendamento nesse horário."
            );
        }

        agendamento.setData(dto.getData());
        agendamento.setHoraInicio(dto.getHoraInicio());
        agendamento.setHoraFim(dto.getHoraFim());
        agendamento.setQuadra(quadraDestino);
        agendamentoRepository.flush();

        return AgendamentoOperationResult.sucesso(AgendamentoMapper.toDTO(agendamento));
    }

    @Transactional
    public AgendamentoResponseDTO alterarProprietarioComoAdmin(Long id, Long usuarioId) {
        if (!SecurityUtils.isAdmin()) {
            throw new AccessDeniedException(
                    "Somente um administrador pode alterar o proprietário do agendamento."
            );
        }

        Agendamento agendamento = buscarParaAtualizacao(id);
        Usuario usuario = usuarioService.buscarEntidadePorId(usuarioId);
        agendamento.setUsuario(usuario);
        agendamentoRepository.flush();

        return AgendamentoMapper.toDTO(agendamento);
    }

    @Transactional
    public AgendamentoOperationResult<AgendamentoResponseDTO> confirmar(Long id) {
        if (!SecurityUtils.isAdmin()) {
            throw new AccessDeniedException(
                    "Somente um administrador pode confirmar agendamentos."
            );
        }

        Agendamento agendamento = buscarParaAtualizacao(id);
        Instant agoraNoBanco = postgresClock.agora();

        if (agendamento.getStatus() != StatusAgendamento.PENDENTE) {
            return AgendamentoOperationResult.conflito(
                    "Somente agendamentos PENDENTES dentro do prazo podem ser confirmados."
            );
        }
        if (estaVencido(agendamento, agoraNoBanco)) {
            agendamento.setStatus(StatusAgendamento.EXPIRADO);
            agendamentoRepository.flush();
            return AgendamentoOperationResult.conflito(
                    "O prazo do agendamento expirou; ele não pode ser confirmado."
            );
        }

        agendamento.setStatus(StatusAgendamento.CONFIRMADO);
        agendamentoRepository.flush();
        return AgendamentoOperationResult.sucesso(AgendamentoMapper.toDTO(agendamento));
    }

    @Transactional
    public AgendamentoOperationResult<AgendamentoResponseDTO> cancelar(Long id) {
        Agendamento agendamento = buscarParaAtualizacao(id);
        validarPermissao(agendamento);
        Instant agoraNoBanco = postgresClock.agora();

        if (agendamento.getStatus() == StatusAgendamento.PENDENTE
                && estaVencido(agendamento, agoraNoBanco)) {
            agendamento.setStatus(StatusAgendamento.EXPIRADO);
            agendamentoRepository.flush();
            return AgendamentoOperationResult.conflito(
                    "O prazo do agendamento expirou; ele não pode ser cancelado."
            );
        }

        if (agendamento.getStatus() != StatusAgendamento.PENDENTE
                && agendamento.getStatus() != StatusAgendamento.CONFIRMADO) {
            return AgendamentoOperationResult.conflito(
                    "Somente agendamentos PENDENTES ou CONFIRMADOS podem ser cancelados."
            );
        }

        agendamento.setStatus(StatusAgendamento.CANCELADO);
        agendamentoRepository.flush();
        return AgendamentoOperationResult.sucesso(AgendamentoMapper.toDTO(agendamento));
    }

    private Agendamento buscarParaAtualizacao(Long id) {
        return agendamentoRepository.buscarPorIdParaAtualizacao(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Agendamento não encontrado."));
    }

    private int expirarPendentesVencidos(List<Agendamento> agendamentos, Instant agora) {
        int expirados = 0;
        for (Agendamento agendamento : agendamentos) {
            if (agendamento.getStatus() == StatusAgendamento.PENDENTE
                    && estaVencido(agendamento, agora)) {
                agendamento.setStatus(StatusAgendamento.EXPIRADO);
                expirados++;
            }
        }
        return expirados;
    }

    private boolean estaVencido(Agendamento agendamento, Instant agoraNoBanco) {
        return !agoraNoBanco.isBefore(
                agendamento.getCriadoEm().plusSeconds(PRAZO_EXPIRACAO_HORAS * 60 * 60)
        );
    }

    private String validarEstadoEPermissaoParaReagendar(Agendamento agendamento) {
        if (agendamento.getStatus() == StatusAgendamento.PENDENTE) {
            validarPermissao(agendamento);
            return null;
        }
        if (agendamento.getStatus() == StatusAgendamento.CONFIRMADO) {
            if (!SecurityUtils.isAdmin()) {
                throw new AccessDeniedException(
                        "Somente um administrador pode reagendar agendamentos confirmados."
                );
            }
            return null;
        }
        return "Agendamentos CANCELADOS ou EXPIRADOS não podem ser reagendados.";
    }

    private boolean existeConflito(
            List<Agendamento> agendamentos,
            LocalTime horaInicio,
            LocalTime horaFim,
            Long idIgnorado) {
        return agendamentos.stream()
                .filter(a -> a.getStatus() == StatusAgendamento.PENDENTE
                        || a.getStatus() == StatusAgendamento.CONFIRMADO)
                .filter(a -> !Objects.equals(a.getId(), idIgnorado))
                .anyMatch(a -> horaInicio.isBefore(a.getHoraFim())
                        && horaFim.isAfter(a.getHoraInicio()));
    }

    private void validarPermissao(Agendamento agendamento) {
        Usuario usuarioLogado = SecurityUtils.getUsuarioLogado();
        boolean dono = agendamento.getUsuario().getId().equals(usuarioLogado.getId());
        if (!dono && !SecurityUtils.isAdmin()) {
            throw new AccessDeniedException(
                    "Você não tem permissão para acessar este agendamento."
            );
        }
    }

    private void validarQuadraAtiva(Quadra quadra) {
        if (!quadra.isAtiva()) {
            throw new BusinessException("A quadra está inativa.");
        }
    }

    private void validarHorarios(LocalTime horaInicio, LocalTime horaFim) {
        if (!horaFim.isAfter(horaInicio)) {
            throw new BusinessException(
                    "O horário de término deve ser depois do horário de início."
            );
        }
    }

    private void validarDataFutura(LocalDate data, LocalTime horaInicio) {
        LocalDateTime inicioAgendamento = LocalDateTime.of(data, horaInicio);
        if (inicioAgendamento.isBefore(LocalDateTime.now())) {
            throw new BusinessException(
                    "Não é possível agendar em uma data/horário que já passou."
            );
        }
    }
}
