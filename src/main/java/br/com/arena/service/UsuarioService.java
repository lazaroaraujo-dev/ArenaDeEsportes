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
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UsuarioService {

    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    private static final String SQLSTATE_INCONSISTENT_STATE = "55000";

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminLifecycleDbService adminLifecycleDbService;
    private final AdminWriterJdbcService adminWriterJdbcService;

    public UsuarioService(
            UsuarioRepository usuarioRepository,
            PasswordEncoder passwordEncoder,
            AdminLifecycleDbService adminLifecycleDbService,
            AdminWriterJdbcService adminWriterJdbcService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminLifecycleDbService = adminLifecycleDbService;
        this.adminWriterJdbcService = adminWriterJdbcService;
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

        String senhaHash = passwordEncoder.encode(dto.getSenha());
        Long id = usuarioRepository.registrarCliente(
                dto.getNome(),
                dto.getEmail(),
                dto.getTelefone(),
                senhaHash
        );

        if (id == null) {
            throw new BusinessException("E-mail já cadastrado.");
        }

        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new BusinessException("E-mail já cadastrado."));

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

        // H-04/V4.3: atualização explícita e determinística. O Hibernate 7 poderia
        // gerar UPDATE com todas as colunas; aqui apenas nome, email e telefone
        // são escritos. Perfil e senha permanecem intactos.
        int atualizados = usuarioRepository.atualizarDadosPessoais(
                id, dto.getNome(), dto.getEmail(), dto.getTelefone()
        );
        if (atualizados == 0) {
            throw new ResourceNotFoundException("Usuário não encontrado.");
        }

        Usuario atualizado = usuarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
        return UsuarioMapper.toDTO(atualizado);
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
     * H-04/V4.3: alteração administrativa de perfil (PATCH /usuarios/{id}/perfil)
     * delegada à função SECURITY DEFINER {@code public.h04_admin_alterar_perfil(...)}
     * executada por arena_admin_writer.
     *
     * <p>O id do ator vem somente do principal autenticado do Spring Security; a
     * função revalida o ator sob o advisory lock. Mutação, contador (via triggers)
     * e auditoria ocorrem na transação própria do writer, commitada antes da
     * tradução do status para o contrato HTTP. Este método não mantém transação
     * do runtime, não adquire lock em segunda conexão e não registra auditoria
     * adicional na aplicação.</p>
     */
    public AdminOperationResult alterarPerfil(Long targetUserId, UsuarioPerfilDTO dto) {
        Usuario ator = obterAtorAutenticado();

        AdminWriterProfileResult resultado;
        try {
            resultado = adminWriterJdbcService.alterarPerfil(
                    ator.getId(),
                    targetUserId,
                    dto.getPerfil().name(),
                    ator.getEmail(),
                    null,
                    null,
                    null
            );
        } catch (DataAccessException ex) {
            tratarFalhaDoWriter(ex);
            // Qualquer outra falha de banco é propagada sem mascaramento (500).
            throw ex;
        }

        return mapearResultadoDaAlteracaoPerfil(resultado, targetUserId);
    }

    private AdminOperationResult mapearResultadoDaAlteracaoPerfil(
            AdminWriterProfileResult resultado, Long targetUserId) {
        return switch (resultado.status()) {
            case SUCCESS -> new AdminOperationResult(
                    AdminOperationStatus.SUCCESS,
                    buscarDTOAtualizado(targetUserId),
                    "ADMIN".equals(resultado.profileAfter())
                            ? "Usuário promovido a ADMIN com sucesso."
                            : "Usuário rebaixado a CLIENTE com sucesso."
            );
            case NO_OP -> new AdminOperationResult(
                    AdminOperationStatus.NO_OP,
                    buscarDTOAtualizado(targetUserId),
                    "Perfil inalterado."
            );
            case TARGET_NOT_FOUND -> new AdminOperationResult(
                    AdminOperationStatus.TARGET_NOT_FOUND,
                    null,
                    "Usuário não encontrado."
            );
            case LAST_ADMIN -> new AdminOperationResult(
                    AdminOperationStatus.LAST_ADMIN,
                    null,
                    "Não é permitido rebaixar o único administrador do sistema."
            );
        };
    }

    /** Leitura somente após o commit do writer; não participa da operação administrativa. */
    private UsuarioResponseDTO buscarDTOAtualizado(Long targetUserId) {
        return usuarioRepository.findById(targetUserId)
                .map(UsuarioMapper::toDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }

    /**
     * H-04/V4.2: exclusão administrativa delegada à função SECURITY DEFINER
     * {@code public.h04_admin_excluir_usuario(...)}, executada por arena_admin_writer.
     *
     * <p>O id do ator vem somente do principal autenticado do Spring Security; a
     * função revalida o ator sob o advisory lock. Mutação, validação e auditoria
     * ocorrem na transação própria do writer, commitada antes da tradução de status
     * para exceções HTTP. Este método não usa DELETE do runtime, não participa de
     * transação do runtime e não registra auditoria adicional.</p>
     */
    public AdminOperationResult deletarAdministrativo(Long targetUserId) {
        Usuario ator = obterAtorAutenticado();

        AdminWriterDeleteResult resultado;
        try {
            resultado = adminWriterJdbcService.excluirUsuario(
                    ator.getId(),
                    targetUserId,
                    ator.getEmail(),
                    null,
                    null,
                    null
            );
        } catch (DataAccessException ex) {
            tratarFalhaDoWriter(ex);
            // Qualquer outra falha de banco é propagada sem mascaramento (500).
            throw ex;
        }

        return mapearResultadoDaExclusao(resultado);
    }

    /**
     * Identidade do operador exclusivamente a partir do principal autenticado.
     * Nenhum identificador enviado no corpo, na query string ou em parâmetros livres
     * do cliente é considerado.
     */
    private Usuario obterAtorAutenticado() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Usuario usuarioLogado)
                || usuarioLogado.getId() == null) {
            throw new AccessDeniedException("Operador não autenticado.");
        }
        return usuarioLogado;
    }

    private AdminOperationResult mapearResultadoDaExclusao(AdminWriterDeleteResult resultado) {
        return switch (resultado.status()) {
            case SUCCESS -> new AdminOperationResult(
                    AdminOperationStatus.SUCCESS,
                    null,
                    "ADMIN".equals(resultado.profileBefore())
                            ? "Administrador excluído com sucesso."
                            : "Cliente excluído com sucesso."
            );
            case TARGET_NOT_FOUND -> new AdminOperationResult(
                    AdminOperationStatus.TARGET_NOT_FOUND,
                    null,
                    "Usuário não encontrado."
            );
            case LAST_ADMIN -> new AdminOperationResult(
                    AdminOperationStatus.LAST_ADMIN,
                    null,
                    "Não é permitido excluir o único administrador do sistema."
            );
            case HAS_AGENDAMENTOS -> throw new BusinessException(
                    "Não é possível excluir um usuário que possui agendamentos."
            );
        };
    }

    /**
     * A função sinaliza falhas técnicas por SQLSTATE. SECURITY DEFINER não autentica
     * o usuário final: 42501 (ator inexistente ou não ADMIN) continua sendo 403, e
     * 55000 (estado administrativo inconsistente) é erro interno, sem conversão
     * para um status de negócio.
     */
    private static void tratarFalhaDoWriter(DataAccessException ex) {
        String sqlState = localizarSqlState(ex);

        if (SQLSTATE_INSUFFICIENT_PRIVILEGE.equals(sqlState)) {
            throw new AccessDeniedException(
                    "Ator autenticado sem autorização para exclusão administrativa.", ex
            );
        }
        if (SQLSTATE_INCONSISTENT_STATE.equals(sqlState)) {
            throw new IllegalStateException(
                    "Falha da função de exclusão administrativa: "
                            + "estado administrativo inconsistente.", ex
            );
        }
    }

    private static String localizarSqlState(Throwable erro) {
        for (Throwable causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sqlException
                    && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    @Deprecated
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
