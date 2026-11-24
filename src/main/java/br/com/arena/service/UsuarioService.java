package br.com.arena.service;

import br.com.arena.dto.usuario.*;
import br.com.arena.enumerate.Perfil;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.LastAdminException;
import br.com.arena.exception.ResourceNotFoundException;
import br.com.arena.mapper.UsuarioMapper;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminLifecycleDbService adminLifecycleDbService;

    public UsuarioService(
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder,
            AdminLifecycleDbService adminLifecycleDbService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminLifecycleDbService = adminLifecycleDbService;
    }

    public List<UsuarioResponseDTO> listarTodos() {
        return usuarioRepository.findAll()
                .stream()
                .map(UsuarioMapper::toDTO)
                .collect(Collectors.toList());
    }

    public UsuarioResponseDTO buscarPorId(Long id) {
        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Usuário não encontrado."));

        return UsuarioMapper.toDTO(usuario);
    }

    public Usuario buscarEntidadePorId(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Usuário não encontrado."));
    }

    @Transactional
    public UsuarioResponseDTO salvar(UsuarioCadastroDTO dto) {
        if (usuarioRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new BusinessException("E-mail já cadastrado.");
        }

        Usuario usuario = UsuarioMapper.toEntity(dto);
        usuario.setSenha(passwordEncoder.encode(dto.getSenha()));
        // H-04: Cadastro público cria estritamente perfil CLIENTE.
        usuario.setPerfil(Perfil.CLIENTE);
        usuario = usuarioRepository.save(usuario);

        return UsuarioMapper.toDTO(usuario);
    }

    @Deprecated
    @Transactional
    public UsuarioResponseDTO salvar(UsuarioRequestDTO dto) {
        UsuarioCadastroDTO cadastroDTO = UsuarioCadastroDTO.builder()
                .nome(dto.getNome())
                .email(dto.getEmail())
                .telefone(dto.getTelefone())
                .senha(dto.getSenha())
                .build();
        return salvar(cadastroDTO);
    }

    @Transactional
    public UsuarioResponseDTO atualizarDadosPessoais(Long id, UsuarioUpdateDTO dto) {
        Usuario usuario = buscarEntidadePorId(id);

        if (!usuario.getEmail().equals(dto.getEmail())
                && usuarioRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new BusinessException("E-mail já cadastrado.");
        }

        usuario.setNome(dto.getNome());
        usuario.setEmail(dto.getEmail());
        usuario.setTelefone(dto.getTelefone());
        // H-04: Atualização de dados pessoais NUNCA altera perfil nem senha.

        usuario = usuarioRepository.save(usuario);
        return UsuarioMapper.toDTO(usuario);
    }

    @Deprecated
    @Transactional
    public UsuarioResponseDTO atualizar(Long id, UsuarioRequestDTO dto) {
        UsuarioUpdateDTO updateDTO = UsuarioUpdateDTO.builder()
                .nome(dto.getNome())
                .email(dto.getEmail())
                .telefone(dto.getTelefone())
                .build();
        return atualizarDadosPessoais(id, updateDTO);
    }

    @Deprecated
    @Transactional
    public UsuarioResponseDTO atualizarProprioPerfil(Long id, UsuarioRequestDTO dto) {
        UsuarioUpdateDTO updateDTO = UsuarioUpdateDTO.builder()
                .nome(dto.getNome())
                .email(dto.getEmail())
                .telefone(dto.getTelefone())
                .build();
        return atualizarDadosPessoais(id, updateDTO);
    }

    /**
     * H-04 (Achado A): Revalida a identidade e permissões do operador sob o advisory lock.
     * Impede condições de corrida (TOCTOU) onde o operador foi rebaixado ou excluído concorrentemente
     * enquanto aguardava na fila do lock.
     */
    private Usuario revalidarOperadorAdminOuFalhar() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Usuario usuarioLogado)) {
            throw new AccessDeniedException("Operador não autenticado.");
        }

        Usuario actor = usuarioRepository.findById(usuarioLogado.getId())
                .orElseThrow(() -> new AccessDeniedException("Operador não encontrado no sistema."));

        if (actor.getPerfil() != Perfil.ADMIN) {
            throw new AccessDeniedException("Operador não possui privilégios de administrador.");
        }

        return actor;
    }

    /**
     * H-04: Gerenciamento administrativo de perfil (PATCH /usuarios/{id}/perfil).
     * Executa sob advisory lock comum, valida consistencia do estado e protege o ultimo ADMIN.
     * Rejeicoes previsiveis (LAST_ADMIN) gravam auditoria na mesma transacao e retornam resultado funcional.
     */
    @Transactional
    public AdminOperationResult alterarPerfil(Long targetUserId, UsuarioPerfilDTO dto) {
        adminLifecycleDbService.adquirirLock();
        Usuario actor = revalidarOperadorAdminOuFalhar();
        AdminLifecycleDbService.AdminLifecycleStateSnapshot state =
                adminLifecycleDbService.validarConsistenciaOuFalhar();

        Usuario alvo = usuarioRepository.findById(targetUserId).orElse(null);
        if (alvo == null) {
            return new AdminOperationResult(
                    AdminOperationStatus.TARGET_NOT_FOUND,
                    null,
                    "Usuário não encontrado."
            );
        }

        Perfil perfilAtual = alvo.getPerfil();
        Perfil novoPerfil = dto.getPerfil();

        if (perfilAtual == novoPerfil) {
            return new AdminOperationResult(
                    AdminOperationStatus.NO_OP,
                    UsuarioMapper.toDTO(alvo),
                    "Perfil inalterado."
            );
        }

        if (perfilAtual == Perfil.CLIENTE && novoPerfil == Perfil.ADMIN) {
            alvo.setPerfil(Perfil.ADMIN);
            usuarioRepository.saveAndFlush(alvo);

            adminLifecycleDbService.registrarAuditoria(
                    "PROMOTE",
                    "SUCCEEDED",
                    "HTTP",
                    "USER",
                    actor != null ? actor.getId() : null,
                    actor != null ? actor.getEmail() : "anonymous",
                    alvo.getId(),
                    alvo.getEmail(),
                    "CLIENTE",
                    "ADMIN",
                    null,
                    null,
                    null,
                    null
            );

            return new AdminOperationResult(
                    AdminOperationStatus.SUCCESS,
                    UsuarioMapper.toDTO(alvo),
                    "Usuário promovido a ADMIN com sucesso."
            );
        }

        if (perfilAtual == Perfil.ADMIN && novoPerfil == Perfil.CLIENTE) {
            if (state.adminCount() <= 1) {
                // H-04: Ultimo ADMIN protegido contra rebaixamento.
                // Registra auditoria REJECTED na mesma transacao e comita sem mutacao.
                adminLifecycleDbService.registrarAuditoria(
                        "DEMOTE",
                        "REJECTED",
                        "HTTP",
                        "USER",
                        actor != null ? actor.getId() : null,
                        actor != null ? actor.getEmail() : "anonymous",
                        alvo.getId(),
                        alvo.getEmail(),
                        "ADMIN",
                        "CLIENTE",
                        "Tentativa de rebaixamento do último administrador",
                        null,
                        null,
                        "LAST_ADMIN"
                );

                return new AdminOperationResult(
                        AdminOperationStatus.LAST_ADMIN,
                        UsuarioMapper.toDTO(alvo),
                        "Não é permitido rebaixar o único administrador do sistema."
                );
            }

            alvo.setPerfil(Perfil.CLIENTE);
            usuarioRepository.saveAndFlush(alvo);

            adminLifecycleDbService.registrarAuditoria(
                    "DEMOTE",
                    "SUCCEEDED",
                    "HTTP",
                    "USER",
                    actor != null ? actor.getId() : null,
                    actor != null ? actor.getEmail() : "anonymous",
                    alvo.getId(),
                    alvo.getEmail(),
                    "ADMIN",
                    "CLIENTE",
                    null,
                    null,
                    null,
                    null
            );

            return new AdminOperationResult(
                    AdminOperationStatus.SUCCESS,
                    UsuarioMapper.toDTO(alvo),
                    "Usuário rebaixado a CLIENTE com sucesso."
            );
        }

        throw new IllegalArgumentException("Transição de perfil desconhecida.");
    }

    /**
     * H-04: Exclusao administrativa de usuario (DELETE /usuarios/{id}).
     * Respeita integridade com agendamentos, valida preventivamente a consistencia
     * do estado administrativo sob advisory lock e impede exclusao do ultimo ADMIN.
     */
    @Transactional
    public AdminOperationResult deletarAdministrativo(Long targetUserId) {
        adminLifecycleDbService.adquirirLock();
        Usuario actor = revalidarOperadorAdminOuFalhar();
        AdminLifecycleDbService.AdminLifecycleStateSnapshot state =
                adminLifecycleDbService.validarConsistenciaOuFalhar();

        Usuario alvo = usuarioRepository.findById(targetUserId).orElse(null);
        if (alvo == null) {
            return new AdminOperationResult(
                    AdminOperationStatus.TARGET_NOT_FOUND,
                    null,
                    "Usuário não encontrado."
            );
        }

        if (!alvo.getAgendamentos().isEmpty()) {
            throw new BusinessException(
                    "Não é possível excluir um usuário que possui agendamentos."
            );
        }

        if (alvo.getPerfil() == Perfil.ADMIN) {
            if (state.adminCount() <= 1) {
                // H-04: Ultimo ADMIN protegido contra exclusao.
                adminLifecycleDbService.registrarAuditoria(
                        "ADMIN_DELETE",
                        "REJECTED",
                        "HTTP",
                        "USER",
                        actor != null ? actor.getId() : null,
                        actor != null ? actor.getEmail() : "anonymous",
                        alvo.getId(),
                        alvo.getEmail(),
                        "ADMIN",
                        null,
                        "Tentativa de exclusão do último administrador",
                        null,
                        null,
                        "LAST_ADMIN"
                );

                return new AdminOperationResult(
                        AdminOperationStatus.LAST_ADMIN,
                        null,
                        "Não é permitido excluir o único administrador do sistema."
                );
            }

            usuarioRepository.delete(alvo);
            usuarioRepository.flush();

            adminLifecycleDbService.registrarAuditoria(
                    "ADMIN_DELETE",
                    "SUCCEEDED",
                    "HTTP",
                    "USER",
                    actor != null ? actor.getId() : null,
                    actor != null ? actor.getEmail() : "anonymous",
                    alvo.getId(),
                    alvo.getEmail(),
                    "ADMIN",
                    null,
                    null,
                    null,
                    null,
                    null
            );

            return new AdminOperationResult(
                    AdminOperationStatus.SUCCESS,
                    null,
                    "Administrador excluído com sucesso."
            );
        }

        // Exclusao de CLIENTE
        usuarioRepository.delete(alvo);
        return new AdminOperationResult(
                AdminOperationStatus.SUCCESS,
                null,
                "Cliente excluído com sucesso."
        );
    }

    @Deprecated
    @Transactional(noRollbackFor = LastAdminException.class)
    public void deletar(Long id) {
        AdminOperationResult result = deletarAdministrativo(id);
        if (result.status() == AdminOperationStatus.TARGET_NOT_FOUND) {
            throw new ResourceNotFoundException(result.message());
        }
        if (result.status() == AdminOperationStatus.LAST_ADMIN) {
            throw new LastAdminException(result.message());
        }
    }
}
