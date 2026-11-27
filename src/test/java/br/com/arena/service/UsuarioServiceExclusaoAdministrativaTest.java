package br.com.arena.service;

import br.com.arena.dto.usuario.AdminOperationStatus;
import br.com.arena.enumerate.Perfil;
import br.com.arena.exception.BusinessException;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Etapa 3 — integração da exclusão administrativa ao UsuarioService, sem banco.
 * Verifica identidade do ator, mapeamento de status e ausência de DELETE/auditoria
 * pelo datasource runtime.
 */
class UsuarioServiceExclusaoAdministrativaTest {

    private static final long ACTOR_ID = 11L;
    private static final long TARGET_ID = 22L;
    private static final String ACTOR_EMAIL = "admin@exemplo.invalid";

    private UsuarioRepository usuarioRepository;
    private AdminLifecycleDbService adminLifecycleDbService;
    private AdminWriterJdbcService adminWriterJdbcService;
    private UsuarioService usuarioService;

    @BeforeEach
    void setUp() {
        usuarioRepository = mock(UsuarioRepository.class);
        adminLifecycleDbService = mock(AdminLifecycleDbService.class);
        adminWriterJdbcService = mock(AdminWriterJdbcService.class);
        usuarioService = new UsuarioService(
                usuarioRepository,
                mock(PasswordEncoder.class),
                adminLifecycleDbService,
                adminWriterJdbcService
        );
        autenticar(usuario(ACTOR_ID, ACTOR_EMAIL, Perfil.ADMIN));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Usuario usuario(Long id, String email, Perfil perfil) {
        return new Usuario(id, "Operador", email, "11999999999", "hash", perfil, 0L, List.of());
    }

    private static void autenticar(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities())
        );
    }

    private static DataAccessException falhaComSqlState(String sqlState) {
        SQLException sqlException = new SQLException("falha simulada", sqlState);
        return new DataAccessException("falha do writer", sqlException) {
        };
    }

    @Test
    void atorVemExclusivamenteDoPrincipalAutenticado() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.SUCCESS, "CLIENTE", "alvo@exemplo.invalid"));

        usuarioService.deletarAdministrativo(TARGET_ID);

        verify(adminWriterJdbcService).excluirUsuario(
                eq(ACTOR_ID), eq(TARGET_ID), eq(ACTOR_EMAIL),
                nullable(String.class), nullable(String.class), nullable(String.class));
        verifyNoInteractions(usuarioRepository, adminLifecycleDbService);
    }

    @Test
    void semPrincipalAutenticadoNaoChamaWriterENegaAcesso() {
        SecurityContextHolder.clearContext();

        assertThrows(
                AccessDeniedException.class,
                () -> usuarioService.deletarAdministrativo(TARGET_ID)
        );
        verifyNoInteractions(adminWriterJdbcService);
    }

    @Test
    void principalSemIdentidadeNaoChamaWriter() {
        autenticar(usuario(null, "sem-id@exemplo.invalid", Perfil.ADMIN));

        assertThrows(
                AccessDeniedException.class,
                () -> usuarioService.deletarAdministrativo(TARGET_ID)
        );
        verifyNoInteractions(adminWriterJdbcService);
    }

    @Test
    void sucessoDeAdminMantemMensagemDoContrato() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.SUCCESS, "ADMIN", "alvo@exemplo.invalid"));

        var resultado = usuarioService.deletarAdministrativo(TARGET_ID);

        assertEquals(AdminOperationStatus.SUCCESS, resultado.status());
        assertEquals("Administrador excluído com sucesso.", resultado.message());
    }

    @Test
    void sucessoDeClienteMantemMensagemDoContrato() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.SUCCESS, "CLIENTE", "alvo@exemplo.invalid"));

        var resultado = usuarioService.deletarAdministrativo(TARGET_ID);

        assertEquals(AdminOperationStatus.SUCCESS, resultado.status());
        assertEquals("Cliente excluído com sucesso.", resultado.message());
    }

    @Test
    void targetNotFoundViraStatus404DoContrato() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.TARGET_NOT_FOUND, null, null));

        var resultado = usuarioService.deletarAdministrativo(TARGET_ID);

        assertEquals(AdminOperationStatus.TARGET_NOT_FOUND, resultado.status());
        assertEquals("Usuário não encontrado.", resultado.message());
    }

    @Test
    void lastAdminViraStatus409DoContrato() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.LAST_ADMIN, "ADMIN", ACTOR_EMAIL));

        var resultado = usuarioService.deletarAdministrativo(TARGET_ID);

        assertEquals(AdminOperationStatus.LAST_ADMIN, resultado.status());
        assertEquals("Não é permitido excluir o único administrador do sistema.", resultado.message());
    }

    @Test
    void hasAgendamentosViraBusinessException400DoContrato() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.HAS_AGENDAMENTOS, "CLIENTE", "alvo@exemplo.invalid"));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> usuarioService.deletarAdministrativo(TARGET_ID)
        );

        assertEquals("Não é possível excluir um usuário que possui agendamentos.", ex.getMessage());
    }

    @Test
    void sqlstate42501DoWriterViraAccessDenied403() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(falhaComSqlState("42501"));

        assertThrows(
                AccessDeniedException.class,
                () -> usuarioService.deletarAdministrativo(TARGET_ID)
        );
    }

    @Test
    void sqlstate55000DoWriterEhErroInternoSemMascaramento() {
        DataAccessException falha = falhaComSqlState("55000");
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(falha);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> usuarioService.deletarAdministrativo(TARGET_ID)
        );

        assertTrue(ex.getMessage().contains("estado administrativo inconsistente"));
        assertSame(falha, ex.getCause());
    }

    @Test
    void outroErroDeBancoNaoEhMascarado() {
        DataAccessException falha = falhaComSqlState("08006");
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(falha);

        DataAccessException ex = assertThrows(
                DataAccessException.class,
                () -> usuarioService.deletarAdministrativo(TARGET_ID)
        );
        assertSame(falha, ex);
    }

    @Test
    void metodoLegadoContinuaMapeandoLastAdminETargetNotFound() {
        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.LAST_ADMIN, "ADMIN", ACTOR_EMAIL));

        assertThrows(
                br.com.arena.exception.LastAdminException.class,
                () -> usuarioService.deletar(TARGET_ID)
        );

        when(adminWriterJdbcService.excluirUsuario(
                anyLong(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new AdminWriterDeleteResult(
                        AdminWriterDeleteStatus.TARGET_NOT_FOUND, null, null));

        assertThrows(
                br.com.arena.exception.ResourceNotFoundException.class,
                () -> usuarioService.deletar(TARGET_ID)
        );
    }
}
