package br.com.arena.controller;

import br.com.arena.dto.usuario.AdminOperationResult;
import br.com.arena.dto.usuario.AdminOperationStatus;
import br.com.arena.dto.usuario.UsuarioPerfilDTO;
import br.com.arena.enumerate.Perfil;
import br.com.arena.enumerate.StatusAgendamento;
import br.com.arena.enumerate.TipoQuadra;
import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import br.com.arena.model.Usuario;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.repository.QuadraRepository;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.JwtService;
import br.com.arena.service.AdminLifecycleDbService;
import br.com.arena.service.UsuarioService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class UsuarioPerfilAdminIntegrationTest {

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

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AdminLifecycleDbService adminLifecycleDbService;

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setupBanco() {
        String dbName = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        assertEquals(EXPECTED_TEST_DATABASE, dbName, "Os testes devem rodar exclusivamente em " + EXPECTED_TEST_DATABASE);
        garantirEstadoAtivoComAdmin();
        limparAdminsSobressalentes();
    }

    @AfterEach
    void limparContextoSeguranca() {
        SecurityContextHolder.clearContext();
    }

    private void limparAdminsSobressalentes() {
        List<Usuario> admins = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .toList();

        if (admins.size() > 1) {
            for (int i = 1; i < admins.size(); i++) {
                Usuario extra = admins.get(i);
                extra.setPerfil(Perfil.CLIENTE);
                usuarioRepository.saveAndFlush(extra);
            }
        }
    }

    private synchronized void garantirEstadoAtivoComAdmin() {
        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        Map<String, Object> state = migratorJdbc.queryForMap(
                "SELECT bootstrap_consumed, recovery_required, admin_count FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        boolean recoveryRequired = Boolean.TRUE.equals(state.get("recovery_required"));

        if (recoveryRequired) {
            TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
            DriverManagerDataSource offlineDs = new DriverManagerDataSource();
            offlineDs.setUrl(settings.url());
            offlineDs.setUsername(settings.offlineUsername());
            offlineDs.setPassword(settings.offlinePassword());
            JdbcTemplate offlineJdbc = new JdbcTemplate(offlineDs);

            String senhaHash = passwordEncoder.encode("adminMaster123!");
            offlineJdbc.queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Master', 'admin_master@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup inicial para testes', 'TICKET-TEST'
                    )
                    """,
                    Long.class,
                    senhaHash
            );
        }
    }

    private String tokenPara(Usuario usuario) {
        return "Bearer " + jwtService.generateToken(usuario);
    }

    private Usuario obterPrimeiroAdmin() {
        return usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhum ADMIN encontrado no banco de testes."));
    }

    private Usuario criarUsuario(String prefixo, Perfil perfil) {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        Usuario u = new Usuario();
        u.setNome("Usuario " + prefixo + " " + sufixo);
        u.setEmail(prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid");
        u.setTelefone("11988887777");
        u.setSenha(passwordEncoder.encode("senha123!"));
        u.setPerfil(perfil);
        return usuarioRepository.saveAndFlush(u);
    }

    private void autenticarComo(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities())
        );
    }

    private long contarAuditorias(String operation, String outcome) {
        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        Long count = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE operation = ? AND outcome = ?",
                Long.class,
                operation,
                outcome
        );
        return count != null ? count : 0L;
    }

    private long contarAuditoriasComErrorCode(String operation, String outcome, String errorCode) {
        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        Long count = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE operation = ? AND outcome = ? AND error_code = ?",
                Long.class,
                operation,
                outcome,
                errorCode
        );
        return count != null ? count : 0L;
    }

    @Test
    void adminPromoveClienteParaAdminComSucessoEAuditoria() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarUsuario("cliente_para_promover", Perfil.CLIENTE);
        long auditoriasAntes = contarAuditorias("PROMOTE", "SUCCEEDED");

        String json = """
                {
                    "perfil": "ADMIN"
                }
                """;

        mockMvc.perform(patch("/usuarios/" + cliente.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("ADMIN"));

        Usuario alvoAtualizado = usuarioRepository.findById(cliente.getId()).orElseThrow();
        assertEquals(Perfil.ADMIN, alvoAtualizado.getPerfil(), "Usuário deve ter sido promovido a ADMIN no banco");

        long auditoriasDepois = contarAuditorias("PROMOTE", "SUCCEEDED");
        assertEquals(auditoriasAntes + 1, auditoriasDepois, "Operação de promoção deve gerar registro de auditoria SUCCEEDED");
    }

    @Test
    void adminRebaixaOutroAdminQuandoExistemOutrosComSucesso() throws Exception {
        Usuario admin1 = obterPrimeiroAdmin();
        Usuario admin2 = criarUsuario("admin_secundario", Perfil.ADMIN);
        long auditoriasAntes = contarAuditorias("DEMOTE", "SUCCEEDED");

        String json = """
                {
                    "perfil": "CLIENTE"
                }
                """;

        mockMvc.perform(patch("/usuarios/" + admin2.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("CLIENTE"));

        Usuario alvoAtualizado = usuarioRepository.findById(admin2.getId()).orElseThrow();
        assertEquals(Perfil.CLIENTE, alvoAtualizado.getPerfil(), "Admin 2 deve ter sido rebaixado para CLIENTE");

        long auditoriasDepois = contarAuditorias("DEMOTE", "SUCCEEDED");
        assertEquals(auditoriasAntes + 1, auditoriasDepois, "Operação de rebaixamento deve gerar registro de auditoria SUCCEEDED");
    }

    @Test
    void adminNaoConsegueRebaixarUltimoAdminRetorna409EGravaAuditoria() throws Exception {
        // Garantir que existe apenas 1 ADMIN no sistema
        List<Usuario> admins = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .toList();

        if (admins.size() > 1) {
            for (int i = 1; i < admins.size(); i++) {
                Usuario extra = admins.get(i);
                extra.setPerfil(Perfil.CLIENTE);
                usuarioRepository.saveAndFlush(extra);
            }
        }

        Usuario ultimoAdmin = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .findFirst()
                .orElseThrow();

        long auditoriasRejectedAntes = contarAuditoriasComErrorCode("DEMOTE", "REJECTED", "LAST_ADMIN");

        String json = """
                {
                    "perfil": "CLIENTE"
                }
                """;

        mockMvc.perform(patch("/usuarios/" + ultimoAdmin.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(ultimoAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Não é permitido rebaixar o único administrador do sistema."));

        Usuario adminPersistido = usuarioRepository.findById(ultimoAdmin.getId()).orElseThrow();
        assertEquals(Perfil.ADMIN, adminPersistido.getPerfil(), "Último admin não pode ser rebaixado");

        long auditoriasRejectedDepois = contarAuditoriasComErrorCode("DEMOTE", "REJECTED", "LAST_ADMIN");
        assertEquals(auditoriasRejectedAntes + 1, auditoriasRejectedDepois,
                "Rejeição previsível LAST_ADMIN deve persistir evento de auditoria com REJECTED");
    }

    @Test
    void adminNaoConsegueExcluirUltimoAdminRetorna409EGravaAuditoria() throws Exception {
        // Garantir que existe apenas 1 ADMIN
        List<Usuario> admins = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .toList();

        if (admins.size() > 1) {
            for (int i = 1; i < admins.size(); i++) {
                Usuario extra = admins.get(i);
                extra.setPerfil(Perfil.CLIENTE);
                usuarioRepository.saveAndFlush(extra);
            }
        }

        Usuario unicoAdmin = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .findFirst()
                .orElseThrow();

        long auditoriasRejectedAntes = contarAuditoriasComErrorCode("ADMIN_DELETE", "REJECTED", "LAST_ADMIN");

        mockMvc.perform(delete("/usuarios/" + unicoAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(unicoAdmin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Não é permitido excluir o único administrador do sistema."));

        assertTrue(usuarioRepository.existsById(unicoAdmin.getId()), "Último admin não pode ser excluído");

        long auditoriasRejectedDepois = contarAuditoriasComErrorCode("ADMIN_DELETE", "REJECTED", "LAST_ADMIN");
        assertEquals(auditoriasRejectedAntes + 1, auditoriasRejectedDepois,
                "Rejeição previsível LAST_ADMIN de exclusão deve persistir evento de auditoria com REJECTED");
    }

    @Test
    void adminExcluiOutroAdminQuandoExistemOutrosComSucesso() throws Exception {
        Usuario admin1 = obterPrimeiroAdmin();
        Usuario admin2 = criarUsuario("admin_para_excluir", Perfil.ADMIN);
        long auditoriasAntes = contarAuditorias("ADMIN_DELETE", "SUCCEEDED");

        mockMvc.perform(delete("/usuarios/" + admin2.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin1)))
                .andExpect(status().isNoContent());

        assertFalse(usuarioRepository.existsById(admin2.getId()), "Admin 2 deve ter sido excluído");

        long auditoriasDepois = contarAuditorias("ADMIN_DELETE", "SUCCEEDED");
        assertEquals(auditoriasAntes + 1, auditoriasDepois, "Exclusão bem-sucedida de ADMIN deve gerar auditoria");
    }

    @Test
    void adminPodeRebaixarASiMesmoSeHouverOutroAdmin() throws Exception {
        Usuario admin1 = obterPrimeiroAdmin();
        Usuario admin2 = criarUsuario("admin_coadministrador", Perfil.ADMIN);

        String json = """
                {
                    "perfil": "CLIENTE"
                }
                """;

        // Admin 1 rebaixa a si mesmo
        mockMvc.perform(patch("/usuarios/" + admin1.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("CLIENTE"));

        Usuario admin1Persistido = usuarioRepository.findById(admin1.getId()).orElseThrow();
        assertEquals(Perfil.CLIENTE, admin1Persistido.getPerfil(), "Admin 1 pôde rebaixar a si mesmo pois existe Admin 2");
        assertEquals(Perfil.ADMIN, usuarioRepository.findById(admin2.getId()).orElseThrow().getPerfil());
    }

    @Test
    void clienteNaoConsegueAlterarPerfilRetorna403() throws Exception {
        Usuario cliente = criarUsuario("cliente_comum", Perfil.CLIENTE);
        Usuario outroCliente = criarUsuario("outro_cliente", Perfil.CLIENTE);

        String json = """
                {
                    "perfil": "ADMIN"
                }
                """;

        mockMvc.perform(patch("/usuarios/" + outroCliente.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(cliente))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());

        assertEquals(Perfil.CLIENTE, usuarioRepository.findById(outroCliente.getId()).orElseThrow().getPerfil());
    }

    @Test
    void clienteNaoConsegueExcluirUsuarioRetorna403() throws Exception {
        Usuario cliente = criarUsuario("cliente_delete", Perfil.CLIENTE);
        Usuario outro = criarUsuario("alvo_delete", Perfil.CLIENTE);

        mockMvc.perform(delete("/usuarios/" + outro.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(cliente)))
                .andExpect(status().isForbidden());

        assertTrue(usuarioRepository.existsById(outro.getId()));
    }

    @Test
    void exclusaoDeUsuarioComAgendamentosBloqueiaPorIntegridade() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario clienteComAgendamento = criarUsuario("cliente_com_jogo", Perfil.CLIENTE);

        Quadra quadra = new Quadra();
        quadra.setNome("Quadra Teste Integridade " + UUID.randomUUID());
        quadra.setTipo(TipoQuadra.FUTVOLEI);
        quadra.setAtiva(true);
        quadra = quadraRepository.saveAndFlush(quadra);

        Agendamento agendamento = new Agendamento();
        agendamento.setQuadra(quadra);
        agendamento.setUsuario(clienteComAgendamento);
        agendamento.setData(LocalDate.now().plusDays(2));
        agendamento.setHoraInicio(LocalTime.of(10, 0));
        agendamento.setHoraFim(LocalTime.of(11, 0));
        agendamento.setStatus(StatusAgendamento.CONFIRMADO);
        agendamentoRepository.saveAndFlush(agendamento);

        mockMvc.perform(delete("/usuarios/" + clienteComAgendamento.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Não é possível excluir um usuário que possui agendamentos."));

        assertTrue(usuarioRepository.existsById(clienteComAgendamento.getId()),
                "Usuário com agendamentos vinculados não pode ser excluído");
    }

    @Test
    void operacoesConcorrentesNaoConseguemDeixarOSistemaSemAdmin() throws Exception {
        // Preparar exatamente 2 administradores
        Usuario admin1 = obterPrimeiroAdmin();
        Usuario admin2 = criarUsuario("admin_concorrente", Perfil.ADMIN);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);

        List<Integer> statusCodes = new CopyOnWriteArrayList<>();

        // Thread 1 tenta rebaixar admin1
        executor.submit(() -> {
            try {
                barrier.await();
                MvcResult res = mockMvc.perform(patch("/usuarios/" + admin1.getId() + "/perfil")
                                .header(HttpHeaders.AUTHORIZATION, tokenPara(admin2))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"perfil\": \"CLIENTE\"}"))
                        .andReturn();
                statusCodes.add(res.getResponse().getStatus());
            } catch (Exception e) {
                statusCodes.add(500);
            } finally {
                latch.countDown();
            }
        });

        // Thread 2 tenta rebaixar admin2
        executor.submit(() -> {
            try {
                barrier.await();
                MvcResult res = mockMvc.perform(patch("/usuarios/" + admin2.getId() + "/perfil")
                                .header(HttpHeaders.AUTHORIZATION, tokenPara(admin1))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"perfil\": \"CLIENTE\"}"))
                        .andReturn();
                statusCodes.add(res.getResponse().getStatus());
            } catch (Exception e) {
                statusCodes.add(500);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Operações concorrentes devem terminar em 10s");
        executor.shutdown();

        // Verificações essenciais:
        // Pelo menos uma das tentativas DEVE ter sido rejeitada (409 Conflict pelo lock ou 403 por revogação de credenciais)
        boolean rejeitada = statusCodes.contains(409) || statusCodes.contains(403);
        assertTrue(rejeitada, "Pelo menos uma das operações concorrentes deve ser rejeitada. Status recebidos: " + statusCodes);

        // No máximo uma operação pode ter sucesso (200 OK)
        long sucessos = statusCodes.stream().filter(s -> s == 200).count();
        assertTrue(sucessos <= 1, "Não podem ocorrer 2 sucessos em rebaixar simultaneamente os últimos administradores. Sucessos: " + sucessos);

        // E a invariante central: NUNCA pode ter ficado zero admins!
        long adminsRestantes = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .count();

        assertTrue(adminsRestantes >= 1, "Invariante violada: a concorrência não pode reduzir a zero admins. Restantes: " + adminsRestantes);
    }

    @Test
    void operacoesConcorrentesRejeitamRebaixamentoDoUltimoAdminCom409() throws Exception {
        Usuario unicoAdmin = obterPrimeiroAdmin();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);

        List<Integer> statusCodes = new CopyOnWriteArrayList<>();

        String json = "{\"perfil\": \"CLIENTE\"}";

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    MvcResult res = mockMvc.perform(patch("/usuarios/" + unicoAdmin.getId() + "/perfil")
                                    .header(HttpHeaders.AUTHORIZATION, tokenPara(unicoAdmin))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json))
                            .andReturn();
                    statusCodes.add(res.getResponse().getStatus());
                } catch (Exception e) {
                    statusCodes.add(500);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Operações concorrentes devem terminar em 10s");
        executor.shutdown();

        assertEquals(2, statusCodes.size());
        assertTrue(statusCodes.stream().allMatch(s -> s == 409),
                "Ambas as tentativas concorrentes de rebaixar o único ADMIN devem retornar 409 Conflict sob o lock. Recebido: " + statusCodes);
        assertEquals(Perfil.ADMIN, usuarioRepository.findById(unicoAdmin.getId()).orElseThrow().getPerfil());
    }

    @Test
    void estadoInconsistenteProvocaFalhaSeguraFailClosed() {
        // Simular inconsistência forçada alterando admin_count no singleton para um valor divergente
        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        migratorJdbc.update("UPDATE public.admin_lifecycle_state SET admin_count = 9999 WHERE singleton_id = 1");

        try {
            assertThrows(IllegalStateException.class, () -> {
                adminLifecycleDbService.validarConsistenciaOuFalhar();
            }, "Divergência entre admin_count e contagem real deve provocar IllegalStateException (fail-closed)");
        } finally {
            // Restaurar coerência para não quebrar outros testes
            long real = adminLifecycleDbService.contarAdminsReais();
            migratorJdbc.update("UPDATE public.admin_lifecycle_state SET admin_count = ? WHERE singleton_id = 1", real);
        }
    }

    // -------------------------------------------------------------------------
    // LAC-02: HTTP 404 em endpoints administrativos com ID inexistente
    // -------------------------------------------------------------------------

    @Test
    void adminTentaAlterarPerfilDeUsuarioInexistenteRetorna404() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        long auditoriasAntes = contarAuditorias("PROMOTE", "SUCCEEDED") + contarAuditorias("DEMOTE", "SUCCEEDED");

        String json = """
                {
                    "perfil": "CLIENTE"
                }
                """;

        mockMvc.perform(patch("/usuarios/999999999/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Usuário não encontrado."));

        // Nenhuma auditoria de mutação deve ter sido criada
        long auditoriasDepois = contarAuditorias("PROMOTE", "SUCCEEDED") + contarAuditorias("DEMOTE", "SUCCEEDED");
        assertEquals(auditoriasAntes, auditoriasDepois, "Operação sobre ID inexistente não deve gerar auditoria de mutação");
    }

    @Test
    void adminTentaExcluirUsuarioInexistenteRetorna404() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        long auditoriasAntes = contarAuditorias("ADMIN_DELETE", "SUCCEEDED");

        mockMvc.perform(delete("/usuarios/999999999")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Usuário não encontrado."));

        long auditoriasDepois = contarAuditorias("ADMIN_DELETE", "SUCCEEDED");
        assertEquals(auditoriasAntes, auditoriasDepois, "DELETE em ID inexistente não deve gerar auditoria de mutação");
    }

    // -------------------------------------------------------------------------
    // LAC-03: Operação NO_OP — PATCH /perfil sem alteração efetiva
    // -------------------------------------------------------------------------

    @Test
    void adminAlteraPerfilParaMesmoValorRetornaNoOpSemAuditoria() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario admin2 = criarUsuario("admin_noop", Perfil.ADMIN);

        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());

        // Captura a contagem total de auditorias ANTES da operação NO_OP
        Long totalAntes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class);
        long auditoriasAntes = totalAntes != null ? totalAntes : 0L;

        // Envia PATCH para o admin2 solicitando o mesmo perfil ADMIN que ele já possui
        String json = """
                {
                    "perfil": "ADMIN"
                }
                """;

        MvcResult result = mockMvc.perform(patch("/usuarios/" + admin2.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn();

        // Perfil permanece ADMIN
        Usuario admin2Persistido = usuarioRepository.findById(admin2.getId()).orElseThrow();
        assertEquals(Perfil.ADMIN, admin2Persistido.getPerfil(),
                "Perfil deve permanecer ADMIN após operação NO_OP");

        // Nenhum novo evento de auditoria deve ter sido gravado
        Long totalDepois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class);
        long auditoriasDepois = totalDepois != null ? totalDepois : 0L;
        assertEquals(auditoriasAntes, auditoriasDepois,
                "Operação NO_OP não deve gerar nenhum novo evento de auditoria");

        // Resposta contém o perfil atual inalterado
        String responseBody = result.getResponse().getContentAsString();
        JsonNode responseNode = objectMapper.readTree(responseBody);
        assertEquals("ADMIN", responseNode.get("perfil").asText(),
                "Resposta HTTP deve refletir o perfil atual (ADMIN) inalterado");
    }

    @Test
    void alterarPerfilServicoRetornaNoOpParaMesmoPerfilSemAuditoria() {
        // LAC-03 (nível de serviço): observa diretamente o status AdminOperationStatus.NO_OP,
        // que não é exposto pelo controller HTTP.
        Usuario admin = obterPrimeiroAdmin();
        Usuario admin2 = criarUsuario("admin_noop_servico", Perfil.ADMIN);
        autenticarComo(admin);

        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        Long totalAntes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class);
        long auditoriasAntes = totalAntes != null ? totalAntes : 0L;

        AdminOperationResult resultado = usuarioService.alterarPerfil(
                admin2.getId(),
                UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build()
        );

        assertEquals(AdminOperationStatus.NO_OP, resultado.status(),
                "O serviço deve devolver explicitamente NO_OP quando o perfil já é o solicitado");
        assertEquals("Perfil inalterado.", resultado.message());
        assertEquals(Perfil.ADMIN, resultado.usuario().getPerfil(),
                "O DTO retornado deve refletir o perfil inalterado");

        assertEquals(Perfil.ADMIN,
                usuarioRepository.findById(admin2.getId()).orElseThrow().getPerfil(),
                "Nenhuma mutação de perfil deve ocorrer em NO_OP");

        Long totalDepois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class);
        assertEquals(auditoriasAntes, totalDepois != null ? totalDepois : 0L,
                "NO_OP não pode gravar nenhum evento de auditoria");
    }

    // -------------------------------------------------------------------------
    // LAC-01: Exclusões concorrentes dos últimos dois administradores via DELETE
    // -------------------------------------------------------------------------

    @Test
    void exclusoesConcorrentesDeUltimosAdminsNaoDeixamSistemaSemAdmin() throws Exception {
        // Preparar exatamente 2 administradores
        Usuario admin1 = obterPrimeiroAdmin();
        Usuario admin2 = criarUsuario("admin_del_conc", Perfil.ADMIN);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        CountDownLatch latch = new CountDownLatch(2);

        List<Integer> statusCodes = new CopyOnWriteArrayList<>();

        // Thread 1: admin1 tenta excluir admin2 (DELETE /usuarios/{admin2.id})
        executor.submit(() -> {
            try {
                barrier.await();
                MvcResult res = mockMvc.perform(delete("/usuarios/" + admin2.getId())
                                .header(HttpHeaders.AUTHORIZATION, tokenPara(admin1)))
                        .andReturn();
                statusCodes.add(res.getResponse().getStatus());
            } catch (Exception e) {
                statusCodes.add(500);
            } finally {
                latch.countDown();
            }
        });

        // Thread 2: admin2 tenta excluir admin1 (DELETE /usuarios/{admin1.id})
        executor.submit(() -> {
            try {
                barrier.await();
                MvcResult res = mockMvc.perform(delete("/usuarios/" + admin1.getId())
                                .header(HttpHeaders.AUTHORIZATION, tokenPara(admin2)))
                        .andReturn();
                statusCodes.add(res.getResponse().getStatus());
            } catch (Exception e) {
                statusCodes.add(500);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Operações concorrentes devem terminar em 15s");
        executor.shutdown();

        assertEquals(2, statusCodes.size(), "Devem existir exatamente 2 respostas HTTP");

        // Invariante principal: o sistema NUNCA pode ficar com zero admins
        long adminsRestantes = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .count();
        assertTrue(adminsRestantes >= 1,
                "Invariante violada: a concorrência não pode deixar o sistema sem ADMINs. Restantes: " + adminsRestantes);

        // No máximo uma exclusão pode ter sucesso (204 No Content)
        long sucessos = statusCodes.stream().filter(s -> s == 204).count();
        assertTrue(sucessos <= 1,
                "No máximo uma das exclusões concorrentes pode ser concluída (204). Sucessos: " + sucessos + ", códigos: " + statusCodes);

        // A outra deve ser rejeitada com 409 (LAST_ADMIN), 403 (rebaixado sob lock) ou 404 (já excluído)
        boolean rejeitada = statusCodes.stream().anyMatch(s -> s == 409 || s == 403 || s == 404);
        assertTrue(rejeitada,
                "Pelo menos uma requisição deve ser rejeitada (409/403/404). Códigos recebidos: " + statusCodes);

        // Estado final: exatamente 1 admin deve restar
        assertEquals(1, adminsRestantes,
                "Após exclusões concorrentes dos últimos 2 admins, exatamente 1 deve restar. Restantes: " + adminsRestantes);
    }

    @Test
    void adminRebaixadoEnquantoAguardavaLockEhRevalidadoERecebe403() throws Exception {
        // H-04 (Achado A): Concorrência com 3 administradores (ADMIN A, ADMIN B, ADMIN C).
        // ADMIN B inicia operação administrativa e fica aguardando o advisory lock.
        // ADMIN A adquire o lock e rebaixa ADMIN B para CLIENTE.
        // ADMIN A confirma a transação.
        // ADMIN B consegue o lock, deve ser revalidado no banco e receber HTTP 403 Forbidden.
        Usuario adminA = obterPrimeiroAdmin();
        Usuario adminB = criarUsuario("admin_b", Perfil.ADMIN);
        Usuario adminC = criarUsuario("admin_c", Perfil.ADMIN);

        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        Long auditoriasSucessoBAntes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE actor_user_id = ? AND outcome = 'SUCCEEDED'",
                Long.class,
                adminB.getId()
        );
        long sucessosIniciais = auditoriasSucessoBAntes != null ? auditoriasSucessoBAntes : 0L;

        ExecutorService executorA = Executors.newSingleThreadExecutor();
        CountDownLatch lockAcquiredByALatch = new CountDownLatch(1);
        CountDownLatch bPidCapturedLatch = new CountDownLatch(1);
        AtomicLong pidA = new AtomicLong();
        AtomicLong pidB = new AtomicLong();

        List<Integer> statusCodesB = new CopyOnWriteArrayList<>();

        // Thread de ADMIN A
        Future<?> futureA = executorA.submit(() -> {
            org.springframework.transaction.support.DefaultTransactionDefinition def =
                    new org.springframework.transaction.support.DefaultTransactionDefinition();
            def.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            org.springframework.transaction.TransactionStatus status = transactionManager.getTransaction(def);

            try {
                // 1. ADMIN A adquire o lock advisory e registra o PID da própria sessão
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY + ")");
                Integer pidAValue = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                assertNotNull(pidAValue, "PID da sessão de A deve estar disponível");
                pidA.set(pidAValue.longValue());

                Long locksA = jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM pg_locks
                        WHERE pid = ?
                          AND locktype = 'advisory'
                          AND ((classid::bigint << 32) | objid::bigint) = ?
                          AND objsubid = 1
                          AND granted = true
                        """,
                        Long.class,
                        pidA.get(),
                        AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY
                );
                assertEquals(1L, locksA != null ? locksA : 0L,
                        "A sessão de A deve ser a detentora do advisory lock");

                lockAcquiredByALatch.countDown();

                // Aguarda ADMIN B registrar o PID da própria sessão antes de tentar o lock
                assertTrue(bPidCapturedLatch.await(5, TimeUnit.SECONDS), "Thread B deve registrar seu PID");

                // 2. Comprova via pg_locks que a sessão específica de ADMIN B (pidB) está bloqueada
                boolean bBloqueado = false;
                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline) {
                    Integer waiting = jdbcTemplate.queryForObject(
                            """
                            SELECT count(*) FROM pg_locks
                            WHERE pid = ?
                              AND locktype = 'advisory'
                              AND ((classid::bigint << 32) | objid::bigint) = ?
                              AND objsubid = 1
                              AND NOT granted
                            """,
                            Integer.class,
                            pidB.get(),
                            AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY
                    );
                    if (waiting != null && waiting > 0) {
                        bBloqueado = true;
                        break;
                    }
                    Thread.sleep(50);
                }
                assertTrue(bBloqueado,
                        "A sessão " + pidB.get() + " de ADMIN B deve estar bloqueada no advisory lock 7409395520021");

                // 3. ADMIN A rebaixa ADMIN B para CLIENTE
                Usuario bDb = usuarioRepository.findById(adminB.getId()).orElseThrow();
                bDb.setPerfil(Perfil.CLIENTE);
                usuarioRepository.saveAndFlush(bDb);

                // 4. ADMIN A confirma a transação (libera o lock no commit)
                transactionManager.commit(status);
            } catch (Throwable ex) {
                if (!status.isCompleted()) {
                    transactionManager.rollback(status);
                }
                throw new RuntimeException(ex);
            }
        });

        // Aguarda ADMIN A adquirir o lock primeiro
        assertTrue(lockAcquiredByALatch.await(5, TimeUnit.SECONDS), "Thread A deve adquirir o lock");

        // O PID de B é capturado dentro do próprio adquirirLock(), na mesma transação
        // e conexão do serviço. Isso identifica a sessão sem criar uma transação externa
        // que poderia manter o operador em cache JPA antes da revalidação sob lock.
        doAnswer(invocation -> {
            Integer pidBValue = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertNotNull(pidBValue, "PID da sessão de B deve estar disponível");
            pidB.set(pidBValue.longValue());
            bPidCapturedLatch.countDown();
            return invocation.callRealMethod();
        }).when(adminLifecycleDbService).adquirirLock();

        // Thread de ADMIN B: tenta rebaixar ADMIN C para CLIENTE
        String tokenB = tokenPara(adminB);
        ExecutorService executorB = Executors.newSingleThreadExecutor();
        Future<?> futureB = executorB.submit(() -> {
            try {
                String json = "{\"perfil\": \"CLIENTE\"}";
                MvcResult res = mockMvc.perform(patch("/usuarios/" + adminC.getId() + "/perfil")
                                .header(HttpHeaders.AUTHORIZATION, tokenB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                        .andReturn();
                statusCodesB.add(res.getResponse().getStatus());
            } catch (Exception e) {
                statusCodesB.add(500);
            }
        });

        futureA.get(15, TimeUnit.SECONDS);
        futureB.get(15, TimeUnit.SECONDS);

        executorA.shutdown();
        executorB.shutdown();

        // 5. ADMIN B deve ser revalidado no banco e receber HTTP 403
        assertEquals(1, statusCodesB.size(), "Deve conter exatamente 1 status code de resposta");
        assertEquals(403, statusCodesB.get(0), "ADMIN B rebaixado a CLIENTE sob lock deve receber HTTP 403 Forbidden");

        // 6 e 7. ADMIN B não pode alterar nenhum usuário; ADMIN C continua sendo ADMIN
        Usuario adminCPersistido = usuarioRepository.findById(adminC.getId()).orElseThrow();
        assertEquals(Perfil.ADMIN, adminCPersistido.getPerfil(), "ADMIN C deve permanecer como ADMIN");

        Usuario adminBPersistido = usuarioRepository.findById(adminB.getId()).orElseThrow();
        assertEquals(Perfil.CLIENTE, adminBPersistido.getPerfil(), "ADMIN B deve ter sido rebaixado a CLIENTE por A");

        Usuario adminAPersistido = usuarioRepository.findById(adminA.getId()).orElseThrow();
        assertEquals(Perfil.ADMIN, adminAPersistido.getPerfil(), "ADMIN A permanece como ADMIN");

        // 8. A auditoria não pode registrar uma operação administrativa bem-sucedida em nome de B
        Long auditoriasSucessoBDepois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE actor_user_id = ? AND outcome = 'SUCCEEDED'",
                Long.class,
                adminB.getId()
        );
        assertEquals(sucessosIniciais, auditoriasSucessoBDepois != null ? auditoriasSucessoBDepois : 0L,
                "A auditoria não pode registrar nenhuma operação administrativa bem-sucedida em nome de B");
    }
}
