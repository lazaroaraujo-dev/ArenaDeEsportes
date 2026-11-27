package br.com.arena.controller;

import br.com.arena.exception.GlobalExceptionHandler;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.ClientRateLimiter;
import br.com.arena.security.ClientRateLimiterTestSupport;
import br.com.arena.security.JwtService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * V5.9.3 (N2): comprovação HTTP ponta a ponta da conversão de SQLSTATE 40001
 * emitido pela função {@code public.h04_alterar_senha} em PostgreSQL 16 real
 * para HTTP 503 com {@code Retry-After: 1}, sem vazamento de detalhes internos.
 */
@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class AlterarSenhaConcorrenciaHttpIntegrationTest {

    private static final String SENHA_INICIAL = "senhaInicial123";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Flyway flyway;
    @Autowired private ClientRateLimiter clientRateLimiter;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private UsuarioRepository usuarioRepository;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private GlobalExceptionHandler globalExceptionHandler;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate migratorJdbc;

    @BeforeEach
    void setUp() {
        assertEquals("arena_flyway_test",
                jdbcTemplate.queryForObject("SELECT current_database()", String.class));
        migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());

        Map<String, Object> state = migratorJdbc.queryForMap(
                "SELECT recovery_required FROM public.admin_lifecycle_state WHERE singleton_id = 1");
        if (Boolean.TRUE.equals(state.get("recovery_required"))) {
            TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
            DriverManagerDataSource offlineDs = new DriverManagerDataSource();
            offlineDs.setUrl(settings.url());
            offlineDs.setUsername(settings.offlineUsername());
            offlineDs.setPassword(settings.offlinePassword());
            new JdbcTemplate(offlineDs).queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Setup', 'admin_setup_n2@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup para teste N2', 'TICKET-N2'
                    )
                    """,
                    Long.class,
                    passwordEncoder.encode("senha123!")
            );
        }
    }

    @Test
    void duasRequisicoesConcorrentesComMesmoJwtRetornam204E503PorSqlstate40001() throws Exception {
        String email = "n2_" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.invalid";
        usuarioRepository.registrarCliente(
                "Cliente N2", email, "11988887777", passwordEncoder.encode(SENHA_INICIAL));
        Usuario usuario = usuarioRepository.findByEmail(email).orElseThrow();
        String hashAntes = migratorJdbc.queryForObject(
                "SELECT senha FROM public.usuario WHERE id = ?", String.class, usuario.getId());
        String jwt = "Bearer " + jwtService.generateToken(usuario);
        String ipFixo = "198.51.100.250";

        // Sincroniza exatamente após o BCrypt real (encode), mantendo os dois principals
        // carregados pelo JwtAuthenticationFilter com o mesmo hashAntes antes da chamada ao banco.
        CountDownLatch ambosCodificaram = new CountDownLatch(2);
        doAnswer(invocation -> {
            String encoded = (String) invocation.callRealMethod();
            ambosCodificaram.countDown();
            assertTrue(ambosCodificaram.await(15, TimeUnit.SECONDS),
                    "Ambas as requisições concorrentes devem concluir encode() antes da persistência");
            return encoded;
        }).when(passwordEncoder).encode(
                argThat(cs -> cs != null && cs.toString().startsWith("novaSenhaN2_")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch inicio = new CountDownLatch(1);
        List<MockHttpServletResponse> respostas;
        try {
            Future<MockHttpServletResponse> f1 = executor.submit(() -> {
                inicio.await();
                return executarPatchSenha(jwt, ipFixo, "novaSenhaN2_1");
            });
            Future<MockHttpServletResponse> f2 = executor.submit(() -> {
                inicio.await();
                return executarPatchSenha(jwt, ipFixo, "novaSenhaN2_2");
            });
            inicio.countDown();
            respostas = List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        List<MockHttpServletResponse> ordenadas = respostas.stream()
                .sorted(Comparator.comparingInt(MockHttpServletResponse::getStatus))
                .toList();
        assertEquals(List.of(204, 503),
                ordenadas.stream().map(MockHttpServletResponse::getStatus).toList());

        MockHttpServletResponse resposta204 = ordenadas.get(0);
        MockHttpServletResponse resposta503 = ordenadas.get(1);
        assertEquals("", resposta204.getContentAsString());

        assertEquals("1", resposta503.getHeader(HttpHeaders.RETRY_AFTER));
        String corpo503 = resposta503.getContentAsString();
        JsonNode json503 = objectMapper.readTree(corpo503);
        assertFalse(json503.path("timestamp").asText().isBlank());
        assertEquals(503, json503.path("status").asInt());
        assertEquals("Conflito transitório no banco de dados; tente novamente.",
                json503.path("error").asText());

        // Ausência de vazamento de informações internas na resposta HTTP 503.
        assertFalse(corpo503.contains("40001"));
        assertFalse(corpo503.contains("h04_"));
        assertFalse(corpo503.contains("PSQLException"));
        assertFalse(corpo503.contains("SQLException"));
        assertFalse(corpo503.contains("br.com.arena"));
        assertFalse(corpo503.contains(hashAntes));
        assertFalse(corpo503.contains(SENHA_INICIAL));
        assertFalse(corpo503.contains("novaSenhaN2_"));

        // Comprova execução real de usuarioRepository.alterarSenha (h04_alterar_senha no PostgreSQL 16)
        // e presença efetiva de SQLSTATE 40001 na cadeia recebida por GlobalExceptionHandler.
        verify(usuarioRepository, times(2))
                .alterarSenha(eq(usuario.getId()), eq(hashAntes), anyString());

        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(globalExceptionHandler, times(1)).tratarErroGenerico(exceptionCaptor.capture());
        SQLException sql40001 = findSqlExceptionWithState(exceptionCaptor.getValue(), "40001");
        assertNotNull(sql40001,
                "Exceção entregue ao GlobalExceptionHandler deve conter SQLException com SQLSTATE 40001");
        assertEquals("40001", sql40001.getSQLState());

        // Estado final persistido e limpeza do rate limiter sem contabilizar falha de senha.
        Long versao = migratorJdbc.queryForObject(
                "SELECT credential_version FROM public.usuario WHERE id = ?", Long.class, usuario.getId());
        assertEquals(1L, versao);
        String hashDepois = migratorJdbc.queryForObject(
                "SELECT senha FROM public.usuario WHERE id = ?", String.class, usuario.getId());
        assertNotEquals(hashAntes, hashDepois);

        Long eventosAuditoria = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM public.admin_audit_event
                WHERE operation = 'PASSWORD_CHANGE' AND outcome = 'SUCCEEDED'
                  AND actor_user_id = ?
                """, Long.class, usuario.getId());
        assertEquals(1L, eventosAuditoria);

        assertEquals(0, ClientRateLimiterTestSupport.passwordInFlightCount(clientRateLimiter, usuario.getId()));
        assertEquals(0, ClientRateLimiterTestSupport.passwordFailureCount(clientRateLimiter, usuario.getId(), ipFixo));
    }

    private MockHttpServletResponse executarPatchSenha(String jwt, String ipFixo, String novaSenha)
            throws Exception {
        String payload = """
                {"senhaAtual":"%s","novaSenha":"%s","confirmacaoNovaSenha":"%s"}
                """.formatted(SENHA_INICIAL, novaSenha, novaSenha);
        return mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, jwt)
                        .with(request -> {
                            request.setRemoteAddr(ipFixo);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn()
                .getResponse();
    }

    private static SQLException findSqlExceptionWithState(Throwable root, String sqlState) {
        for (Throwable t = root; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return sql;
            }
        }
        return null;
    }
}
