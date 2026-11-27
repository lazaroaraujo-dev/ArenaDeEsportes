package br.com.arena.service;

import br.com.arena.dto.usuario.AdminOperationStatus;
import br.com.arena.dto.usuario.UsuarioPerfilDTO;
import br.com.arena.dto.usuario.UsuarioUpdateDTO;
import br.com.arena.enumerate.Perfil;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** V4.3 — alteração administrativa de perfil via writer e atualização pessoal explícita. */
class UsuarioServiceAlteracaoPerfilTest {

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
        return new Usuario(id, "Operador", email, "11999999999", "hash-antiga", perfil, 0L, List.of());
    }

    private static void autenticar(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities())
        );
    }

    private static DataAccessException falhaComSqlState(String sqlState) {
        return new DataAccessException("falha do writer", new SQLException("falha", sqlState)) {
        };
    }

    private void writerRetorna(AdminWriterProfileStatus status, String before, String after) {
        when(adminWriterJdbcService.alterarPerfil(
                anyLong(), anyLong(), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new AdminWriterProfileResult(status, before, after, "alvo@exemplo.invalid"));
        when(usuarioRepository.findById(TARGET_ID))
                .thenReturn(Optional.of(usuario(TARGET_ID, "alvo@exemplo.invalid",
                        "ADMIN".equals(after) ? Perfil.ADMIN : Perfil.CLIENTE)));
    }

    @Test
    void atorVemDoPrincipalEPerfilVemSomenteDaFuncao() {
        writerRetorna(AdminWriterProfileStatus.SUCCESS, "CLIENTE", "ADMIN");

        var resultado = usuarioService.alterarPerfil(
                TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build());

        assertEquals(AdminOperationStatus.SUCCESS, resultado.status());
        assertEquals(Perfil.ADMIN, resultado.usuario().getPerfil());
        verify(adminWriterJdbcService).alterarPerfil(
                eq(ACTOR_ID), eq(TARGET_ID), eq("ADMIN"), eq(ACTOR_EMAIL),
                nullable(String.class), nullable(String.class), nullable(String.class));
        verify(usuarioRepository, never()).delete(any());
        verify(usuarioRepository, never()).save(any());
        verify(usuarioRepository, never()).saveAndFlush(any());
        verifyNoInteractions(adminLifecycleDbService);
    }

    @Test
    void semPrincipalAutenticadoNaoChamaWriter() {
        SecurityContextHolder.clearContext();

        assertThrows(AccessDeniedException.class,
                () -> usuarioService.alterarPerfil(
                        TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build()));
        verifyNoInteractions(adminWriterJdbcService);
    }

    @Test
    void promotionMantemMensagemDoContrato() {
        writerRetorna(AdminWriterProfileStatus.SUCCESS, "CLIENTE", "ADMIN");

        var resultado = usuarioService.alterarPerfil(
                TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build());

        assertEquals("Usuário promovido a ADMIN com sucesso.", resultado.message());
    }

    @Test
    void demotionMantemMensagemDoContrato() {
        writerRetorna(AdminWriterProfileStatus.SUCCESS, "ADMIN", "CLIENTE");

        var resultado = usuarioService.alterarPerfil(
                TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.CLIENTE).build());

        assertEquals("Usuário rebaixado a CLIENTE com sucesso.", resultado.message());
    }

    @Test
    void noOpMantemMensagemEDtoAtualizado() {
        writerRetorna(AdminWriterProfileStatus.NO_OP, "ADMIN", "ADMIN");

        var resultado = usuarioService.alterarPerfil(
                TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build());

        assertEquals(AdminOperationStatus.NO_OP, resultado.status());
        assertEquals("Perfil inalterado.", resultado.message());
        assertEquals(Perfil.ADMIN, resultado.usuario().getPerfil());
    }

    @Test
    void targetNotFoundMantemMensagem404() {
        writerRetorna(AdminWriterProfileStatus.TARGET_NOT_FOUND, null, null);

        var resultado = usuarioService.alterarPerfil(
                TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build());

        assertEquals(AdminOperationStatus.TARGET_NOT_FOUND, resultado.status());
        assertEquals("Usuário não encontrado.", resultado.message());
    }

    @Test
    void lastAdminMantemMensagem409() {
        writerRetorna(AdminWriterProfileStatus.LAST_ADMIN, "ADMIN", "ADMIN");

        var resultado = usuarioService.alterarPerfil(
                TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.CLIENTE).build());

        assertEquals(AdminOperationStatus.LAST_ADMIN, resultado.status());
        assertEquals("Não é permitido rebaixar o único administrador do sistema.", resultado.message());
    }

    @Test
    void sqlstate42501ViraAccessDenied403() {
        when(adminWriterJdbcService.alterarPerfil(
                anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(falhaComSqlState("42501"));

        assertThrows(AccessDeniedException.class,
                () -> usuarioService.alterarPerfil(
                        TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build()));
    }

    @Test
    void sqlstate55000EhErroInternoSemMascaramento() {
        DataAccessException falha = falhaComSqlState("55000");
        when(adminWriterJdbcService.alterarPerfil(
                anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(falha);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> usuarioService.alterarPerfil(
                        TARGET_ID, UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build()));
        assertSame(falha, ex.getCause());
    }

    @Test
    void atualizacaoPessoalUsaQueryExplicitaSemPerfilNemSenha() throws Exception {
        Method metodo = UsuarioRepository.class.getMethod(
                "atualizarDadosPessoais", Long.class, String.class, String.class, String.class);
        Query query = metodo.getAnnotation(Query.class);

        assertNotNull(query);
        String sql = query.value().toUpperCase();
        assertTrue(sql.contains("SET NOME"));
        assertTrue(sql.contains("EMAIL"));
        assertTrue(sql.contains("TELEFONE"));
        assertFalse(sql.contains("PERFIL"));
        assertFalse(sql.contains("SENHA"));
    }

    @Test
    void atualizacaoPessoalNaoTocaPerfilNemSenha() {
        Usuario usuario = usuario(TARGET_ID, "antigo@exemplo.invalid", Perfil.CLIENTE);
        when(usuarioRepository.findById(TARGET_ID)).thenReturn(
                Optional.of(usuario),
                Optional.of(usuario(TARGET_ID, "novo@exemplo.invalid", Perfil.CLIENTE))
        );
        when(usuarioRepository.findByEmail("novo@exemplo.invalid")).thenReturn(Optional.empty());
        when(usuarioRepository.atualizarDadosPessoais(TARGET_ID, "Novo nome", "novo@exemplo.invalid", "11999990000"))
                .thenReturn(1);

        var dto = usuarioService.atualizarDadosPessoais(TARGET_ID, UsuarioUpdateDTO.builder()
                .nome("Novo nome").email("novo@exemplo.invalid").telefone("11999990000").build());

        verify(usuarioRepository).atualizarDadosPessoais(
                TARGET_ID, "Novo nome", "novo@exemplo.invalid", "11999990000");
        verify(usuarioRepository, never()).save(any());
        verify(usuarioRepository, never()).saveAndFlush(any());
        assertEquals(Perfil.CLIENTE, dto.getPerfil());
    }
}
