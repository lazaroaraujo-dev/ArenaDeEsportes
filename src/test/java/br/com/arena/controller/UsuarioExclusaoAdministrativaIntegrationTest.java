package br.com.arena.controller;

import br.com.arena.dto.usuario.AdminOperationStatus;
import br.com.arena.enumerate.Perfil;
import br.com.arena.enumerate.StatusAgendamento;
import br.com.arena.enumerate.TipoQuadra;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.LastAdminException;
import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import br.com.arena.model.Usuario;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.repository.QuadraRepository;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.JwtService;
import br.com.arena.service.UsuarioService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Etapa 3 — contrato HTTP e durabilidade da auditoria da exclusão administrativa
 * executada por {@code arena_admin_writer}.
 */
@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class UsuarioExclusaoAdministrativaIntegrationTest {

    private static final String EXPECTED_TEST_DATABASE = "arena_flyway_test";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private QuadraRepository quadraRepository;
    @Autowired
    private AgendamentoRepository agendamentoRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Flyway flyway;
    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate migratorJdbc;

    @BeforeEach
    void setupBanco() {
        assertEquals(EXPECTED_TEST_DATABASE,
                jdbcTemplate.queryForObject("SELECT current_database()", String.class));
        migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        migratorJdbc.setQueryTimeout(5);
        garantirEstadoAtivoComAdmin();
        limparAdminsSobressalentes();
    }

    @AfterEach
    void limparContextoSeguranca() {
        SecurityContextHolder.clearContext();
    }

    private synchronized void garantirEstadoAtivoComAdmin() {
        Map<String, Object> state = migratorJdbc.queryForMap(
                "SELECT bootstrap_consumed, recovery_required FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        if (Boolean.TRUE.equals(state.get("recovery_required"))) {
            TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
            DriverManagerDataSource offlineDs = new DriverManagerDataSource();
            offlineDs.setUrl(settings.url());
            offlineDs.setUsername(settings.offlineUsername());
            offlineDs.setPassword(settings.offlinePassword());
            JdbcTemplate offlineJdbc = new JdbcTemplate(offlineDs);
            offlineJdbc.queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Master', 'admin_master@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup inicial para testes', 'TICKET-TEST'
                    )
                    """,
                    Long.class,
                    passwordEncoder.encode("adminMaster123!")
            );
        }
    }

    private void limparAdminsSobressalentes() {
        List<Usuario> admins = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .toList();
        for (int i = 1; i < admins.size(); i++) {
            migratorJdbc.update(
                    "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE id = ?",
                    admins.get(i).getId()
            );
        }
    }

    private Usuario obterPrimeiroAdmin() {
        return usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhum ADMIN encontrado no banco de testes."));
    }

    /** Insere via arena_migrator e devolve entidade detached: o runtime não tem INSERT em usuario. */
    private Usuario criarUsuario(String prefixo, Perfil perfil) {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        Long id = migratorJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES (?, ?, ?, ?, ?::varchar)
                RETURNING id
                """,
                Long.class,
                "Usuario " + prefixo + " " + sufixo,
                prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid",
                "11988887777",
                passwordEncoder.encode("senha123!"),
                perfil.name()
        );
        return new Usuario(
                id, "Usuario " + prefixo, prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid",
                "11988887777", "hash", perfil, 0L, List.of()
        );
    }

    private String tokenPara(Usuario usuario) {
        return "Bearer " + jwtService.generateToken(usuario);
    }

    private void autenticarComo(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities())
        );
    }

    private long contarAuditorias(String operation, String outcome, String errorCode) {
        Long count = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM public.admin_audit_event
                WHERE operation = ? AND outcome = ? AND error_code IS NOT DISTINCT FROM ?
                """,
                Long.class, operation, outcome, errorCode
        );
        return count != null ? count : 0L;
    }

    private void criarAgendamentoPara(Usuario cliente) {
        Quadra quadra = new Quadra();
        quadra.setNome("Quadra exclusão " + UUID.randomUUID());
        quadra.setTipo(TipoQuadra.FUTVOLEI);
        quadra.setAtiva(true);
        quadra = quadraRepository.saveAndFlush(quadra);

        Agendamento agendamento = new Agendamento();
        agendamento.setQuadra(quadra);
        agendamento.setUsuario(cliente);
        agendamento.setData(LocalDate.now().plusDays(3));
        agendamento.setHoraInicio(LocalTime.of(9, 0));
        agendamento.setHoraFim(LocalTime.of(10, 0));
        agendamento.setStatus(StatusAgendamento.CONFIRMADO);
        agendamentoRepository.saveAndFlush(agendamento);
    }

    @Test
    void clienteExcluidoComSucessoRetorna204EAuditoriaClientDelete() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarUsuario("cliente_sucesso", Perfil.CLIENTE);
        long antes = contarAuditorias("CLIENT_DELETE", "SUCCEEDED", null);

        mockMvc.perform(delete("/usuarios/" + cliente.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin)))
                .andExpect(status().isNoContent());

        assertFalse(usuarioRepository.existsById(cliente.getId()));
        assertEquals(antes + 1, contarAuditorias("CLIENT_DELETE", "SUCCEEDED", null));
        Long eventos = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM public.admin_audit_event
                WHERE operation = 'CLIENT_DELETE' AND outcome = 'SUCCEEDED'
                  AND actor_user_id = ? AND actor_ref = ? AND target_user_id = ?
                """,
                Long.class, admin.getId(), admin.getEmail(), cliente.getId()
        );
        assertEquals(1L, eventos != null ? eventos : 0L,
                "A identidade do ator auditada deve ser a do principal autenticado");
    }

    @Test
    void adminExcluidoComSucessoRetorna204EAuditoriaAdminDelete() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario outroAdmin = criarUsuario("admin_sucesso", Perfil.ADMIN);
        long antes = contarAuditorias("ADMIN_DELETE", "SUCCEEDED", null);

        mockMvc.perform(delete("/usuarios/" + outroAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin)))
                .andExpect(status().isNoContent());

        assertFalse(usuarioRepository.existsById(outroAdmin.getId()));
        assertEquals(antes + 1, contarAuditorias("ADMIN_DELETE", "SUCCEEDED", null));
    }

    @Test
    void alvoInexistenteRetorna404SemAuditoria() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        long antes = contarAuditorias("ADMIN_DELETE", "SUCCEEDED", null)
                + contarAuditorias("CLIENT_DELETE", "SUCCEEDED", null);

        mockMvc.perform(delete("/usuarios/999999999")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Usuário não encontrado."));

        assertEquals(antes, contarAuditorias("ADMIN_DELETE", "SUCCEEDED", null)
                + contarAuditorias("CLIENT_DELETE", "SUCCEEDED", null));
    }

    @Test
    void ultimoAdminRetorna409EAuditoriaRejectedCommitada() throws Exception {
        Usuario ultimoAdmin = obterPrimeiroAdmin();
        long antes = contarAuditorias("ADMIN_DELETE", "REJECTED", "LAST_ADMIN");

        mockMvc.perform(delete("/usuarios/" + ultimoAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(ultimoAdmin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("Não é permitido excluir o único administrador do sistema."));

        assertTrue(usuarioRepository.existsById(ultimoAdmin.getId()));
        assertEquals(antes + 1, contarAuditorias("ADMIN_DELETE", "REJECTED", "LAST_ADMIN"),
                "A rejeição LAST_ADMIN deve permanecer auditada após o HTTP 409");
    }

    @Test
    void usuarioComAgendamentoRetorna400EAuditoriaRejectedCommitada() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarUsuario("cliente_agendado", Perfil.CLIENTE);
        criarAgendamentoPara(cliente);
        long antes = contarAuditorias("CLIENT_DELETE", "REJECTED", "HAS_AGENDAMENTOS");

        mockMvc.perform(delete("/usuarios/" + cliente.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Não é possível excluir um usuário que possui agendamentos."));

        assertTrue(usuarioRepository.existsById(cliente.getId()));
        assertEquals(antes + 1, contarAuditorias("CLIENT_DELETE", "REJECTED", "HAS_AGENDAMENTOS"),
                "A rejeição HAS_AGENDAMENTOS deve permanecer auditada após o HTTP 400");
    }

    @Test
    void auditoriaRejectedPermaneceCommitadaQuandoExcecaoSaiDeTransacaoExterna() {
        Usuario ultimoAdmin = obterPrimeiroAdmin();
        autenticarComo(ultimoAdmin);
        long antesLastAdmin = contarAuditorias("ADMIN_DELETE", "REJECTED", "LAST_ADMIN");

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThrows(LastAdminException.class, () -> txTemplate.execute(status -> {
            usuarioService.deletar(ultimoAdmin.getId());
            return null;
        }));

        assertEquals(antesLastAdmin + 1, contarAuditorias("ADMIN_DELETE", "REJECTED", "LAST_ADMIN"),
                "O REQUIRES_NEW do writer comita a auditoria mesmo com rollback da transação externa");

        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarUsuario("cliente_agendado_tx", Perfil.CLIENTE);
        criarAgendamentoPara(cliente);
        long antesAgendamentos = contarAuditorias("CLIENT_DELETE", "REJECTED", "HAS_AGENDAMENTOS");

        assertThrows(BusinessException.class, () -> txTemplate.execute(status -> {
            usuarioService.deletar(cliente.getId());
            return null;
        }));

        assertEquals(antesAgendamentos + 1,
                contarAuditorias("CLIENT_DELETE", "REJECTED", "HAS_AGENDAMENTOS"),
                "HAS_AGENDAMENTOS também deve permanecer auditado após rollback externo");
        assertTrue(usuarioRepository.existsById(admin.getId()));
        assertTrue(usuarioRepository.existsById(cliente.getId()));
    }

    @Test
    void atorNaoAdminRetorna403SemMutacao() throws Exception {
        Usuario cliente = criarUsuario("cliente_ator", Perfil.CLIENTE);
        Usuario alvo = criarUsuario("alvo_ator", Perfil.CLIENTE);

        mockMvc.perform(delete("/usuarios/" + alvo.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(cliente)))
                .andExpect(status().isForbidden());

        assertTrue(usuarioRepository.existsById(alvo.getId()));
    }

    @Test
    void atorInexistenteNoPrincipalEhNegadoPeloWriter() {
        // Não existe param/header para informar ator: a identidade vem do principal.
        Usuario atorFantasma = new Usuario(
                987654321L, "Ator", "fantasma@exemplo.invalid", "11999999999",
                "hash", Perfil.ADMIN, 0L, List.of()
        );
        autenticarComo(atorFantasma);

        assertThrows(AccessDeniedException.class,
                () -> usuarioService.deletarAdministrativo(obterPrimeiroAdmin().getId()));
    }

    @Test
    void estadoAdministrativoInconsistenteRetorna500SemMascarar() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario alvo = criarUsuario("alvo_estado", Perfil.CLIENTE);

        migratorJdbc.update("UPDATE public.admin_lifecycle_state SET admin_count = 9999 WHERE singleton_id = 1");
        try {
            mockMvc.perform(delete("/usuarios/" + alvo.getId())
                            .header(HttpHeaders.AUTHORIZATION, tokenPara(admin)))
                    .andExpect(status().isInternalServerError());
            assertTrue(usuarioRepository.existsById(alvo.getId()),
                    "Falha técnica não pode gerar exclusão parcial");
        } finally {
            long real = usuarioRepository.findAll().stream()
                    .filter(u -> u.getPerfil() == Perfil.ADMIN).count();
            migratorJdbc.update(
                    "UPDATE public.admin_lifecycle_state SET admin_count = ? WHERE singleton_id = 1", real
            );
        }
    }

    @Test
    void runtimeNaoTemDeleteDiretoNemExecuteNaFuncaoDoWriter() {
        assertFalse(Boolean.TRUE.equals(migratorJdbc.queryForObject(
                "SELECT has_table_privilege('arena_runtime', 'public.usuario', 'DELETE')",
                Boolean.class
        )));
        assertFalse(Boolean.TRUE.equals(migratorJdbc.queryForObject(
                """
                SELECT has_function_privilege('arena_runtime',
                    'public.h04_admin_excluir_usuario(bigint, bigint, character varying, text, character varying, character varying)',
                    'EXECUTE')
                """,
                Boolean.class
        )));
        assertTrue(Boolean.TRUE.equals(migratorJdbc.queryForObject(
                """
                SELECT has_function_privilege('arena_admin_writer',
                    'public.h04_admin_excluir_usuario(bigint, bigint, character varying, text, character varying, character varying)',
                    'EXECUTE')
                """,
                Boolean.class
        )));
        Usuario admin = obterPrimeiroAdmin();
        assertNotNull(admin);
        autenticarComo(admin);
        assertEquals(AdminOperationStatus.SUCCESS,
                usuarioService.deletarAdministrativo(
                        criarUsuario("cliente_privilegio", Perfil.CLIENTE).getId()
                ).status());
    }
}
