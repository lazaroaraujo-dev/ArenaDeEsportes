package br.com.arena.controller;

import br.com.arena.exception.BusinessException;
import br.com.arena.exception.RateLimitSource;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.JwtService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V5.8.2 — alteração autenticada de senha e invalidação imediata de JWT. */
@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class AlterarSenhaIntegrationTest {

    private static final String SENHA_INICIAL = "senhaInicial123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Flyway flyway;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger ipSequencia = new AtomicInteger();
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
                        NULL, 'Admin Setup', 'admin_setup_senha@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup para testes de senha', 'TICKET-SENHA'
                    )
                    """,
                    Long.class,
                    passwordEncoder.encode("senha123!")
            );
        }
    }

    private String emailUnico(String prefixo) {
        return prefixo + "_" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.invalid";
    }

    private Usuario criarCliente(String senha) {
        String email = emailUnico("cliente_senha");
        usuarioRepository.registrarCliente(
                "Cliente Senha", email, "11988887777", passwordEncoder.encode(senha));
        return usuarioRepository.findByEmail(email).orElseThrow();
    }

    private static String ip() {
        return "198.51.100." + IP_COUNTER.incrementAndGet();
    }

    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    private String token(Usuario usuario) {
        return "Bearer " + jwtService.generateToken(usuario);
    }

    private long versao(Long id) {
        Long valor = migratorJdbc.queryForObject(
                "SELECT credential_version FROM public.usuario WHERE id = ?", Long.class, id);
        return valor != null ? valor : -1L;
    }

    private String hash(Long id) {
        return migratorJdbc.queryForObject(
                "SELECT senha FROM public.usuario WHERE id = ?", String.class, id);
    }

    private static String json(String atual, String nova, String confirmacao) {
        return """
                {"senhaAtual":"%s","novaSenha":"%s","confirmacaoNovaSenha":"%s"}
                """.formatted(atual, nova, confirmacao);
    }

    private MvcResult patchSenha(Usuario usuario, String ipFixo, String senhaAtual, String novaSenha)
            throws Exception {
        return mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, token(usuario))
                        .with(request -> {
                            request.setRemoteAddr(ipFixo);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(senhaAtual, novaSenha, novaSenha)))
                .andReturn();
    }

    private MvcResult login(String email, String senha) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip());
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}"))
                .andReturn();
    }

    @Test
    void alteracaoBemSucedidaInvalidaTokenAntigoEAceitaNovoLogin() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String tokenAntigo = token(usuario);

        mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, tokenAntigo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SENHA_INICIAL, "novaSenha123", "novaSenha123")))
                .andExpect(status().isNoContent());

        assertEquals(1L, versao(usuario.getId()));
        assertTrue(passwordEncoder.matches("novaSenha123", hash(usuario.getId())));
        assertFalse(passwordEncoder.matches(SENHA_INICIAL, hash(usuario.getId())));

        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenAntigo))
                .andExpect(status().isForbidden());

        assertEquals(401, login(usuario.getEmail(), SENHA_INICIAL).getResponse().getStatus());

        MvcResult resultado = login(usuario.getEmail(), "novaSenha123");
        assertEquals(200, resultado.getResponse().getStatus());
        JsonNode json = objectMapper.readTree(resultado.getResponse().getContentAsString());
        String tokenNovo = json.path("token").asText();
        assertTrue(tokenNovo.length() > 20);

        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenNovo))
                .andExpect(status().isOk());

        Long eventos = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM public.admin_audit_event
                WHERE operation = 'PASSWORD_CHANGE' AND outcome = 'SUCCEEDED'
                  AND actor_user_id = ?
                """, Long.class, usuario.getId());
        assertEquals(1L, eventos != null ? eventos : 0L);
        String reason = migratorJdbc.queryForObject(
                "SELECT reason FROM public.admin_audit_event WHERE operation = 'PASSWORD_CHANGE' AND actor_user_id = ?",
                String.class, usuario.getId());
        assertEquals(null, reason, "auditoria não pode conter segredo");
    }

    @Test
    void senhaAtualIncorretaRetorna400ENaoAlteraNada() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String tokenValido = token(usuario);

        mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, tokenValido)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("senhaErrada", "novaSenha123", "novaSenha123")))
                .andExpect(status().isBadRequest());

        assertEquals(0L, versao(usuario.getId()));
        assertTrue(passwordEncoder.matches(SENHA_INICIAL, hash(usuario.getId())));
        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenValido))
                .andExpect(status().isOk());
    }

    @Test
    void confirmacaoDivergenteENovaSenhaIgualAtualSaoRejeitadas() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String tokenValido = token(usuario);

        mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, tokenValido)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SENHA_INICIAL, "novaSenha123", "outraSenha123")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, tokenValido)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SENHA_INICIAL, SENHA_INICIAL, SENHA_INICIAL)))
                .andExpect(status().isBadRequest());

        assertEquals(0L, versao(usuario.getId()));
    }

    @Test
    void semAutenticacaoNaoAlteraSenha() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);

        mockMvc.perform(patch("/usuarios/me/senha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(SENHA_INICIAL, "novaSenha123", "novaSenha123")))
                .andExpect(status().isForbidden());

        assertEquals(0L, versao(usuario.getId()));
        assertTrue(passwordEncoder.matches(SENHA_INICIAL, hash(usuario.getId())));
    }

    @Test
    void idEnviadoPeloClienteNaoAlteraSenhaDeOutroUsuario() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        Usuario outro = criarCliente(SENHA_INICIAL);
        String hashOutroAntes = hash(outro.getId());

        String jsonComId = """
                {"id":%d,"senhaAtual":"%s","novaSenha":"novaSenha123","confirmacaoNovaSenha":"novaSenha123"}
                """.formatted(outro.getId(), SENHA_INICIAL);

        mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, token(usuario))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonComId))
                .andExpect(status().isNoContent());

        assertEquals(hashOutroAntes, hash(outro.getId()));
        assertEquals(0L, versao(outro.getId()));
        assertEquals(1L, versao(usuario.getId()));
    }

    @Test
    void falhaDaFuncaoNaoAlteraSenhaNemVersao() {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String hashAntes = hash(usuario.getId());

        assertThrows(DataAccessException.class,
                () -> usuarioRepository.alterarSenha(usuario.getId(), hashAntes, ""));

        assertEquals(hashAntes, hash(usuario.getId()));
        assertEquals(0L, versao(usuario.getId()));
    }

    @Test
    void apenasUmaAlteracaoConcorrenteUsaOHashAntigo() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String hashAntes = hash(usuario.getId());
        String hash1 = passwordEncoder.encode("novaSenha111");
        String hash2 = passwordEncoder.encode("novaSenha222");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch hashCapturado = new CountDownLatch(2);
        var sucessos = new java.util.concurrent.atomic.AtomicInteger();
        var vencedor = new java.util.concurrent.atomic.AtomicReference<String>();
        var falhas = new java.util.concurrent.atomic.AtomicInteger();
        try {
            Future<?> f1 = executor.submit(() -> {
                start.await();
                hashCapturado.countDown();
                hashCapturado.await();
                // Ambos usaram o mesmo hash antigo, obtido antes de qualquer commit.
                try {
                    usuarioRepository.alterarSenha(usuario.getId(), hashAntes, hash1);
                    sucessos.incrementAndGet();
                    vencedor.set(hash1);
                } catch (DataAccessException ex) {
                    falhas.incrementAndGet();
                }
                return null;
            });
            Future<?> f2 = executor.submit(() -> {
                start.await();
                hashCapturado.countDown();
                hashCapturado.await();
                try {
                    usuarioRepository.alterarSenha(usuario.getId(), hashAntes, hash2);
                    sucessos.incrementAndGet();
                    vencedor.set(hash2);
                } catch (DataAccessException ex) {
                    falhas.incrementAndGet();
                }
                return null;
            });
            start.countDown();
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, sucessos.get(), "apenas uma alteração pode usar o hash antigo");
        assertEquals(1, falhas.get(), "a segunda alteração deve ser rejeitada sob lock");
        assertEquals(1L, versao(usuario.getId()));
        assertEquals(vencedor.get(), hash(usuario.getId()));

        Long eventos = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM public.admin_audit_event
                WHERE operation = 'PASSWORD_CHANGE' AND outcome = 'SUCCEEDED'
                  AND actor_user_id = ?
                """, Long.class, usuario.getId());
        assertEquals(1L, eventos != null ? eventos : 0L);
    }

    @Test
    void falhaNaAuditoriaReverteSenhaEVersao() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String hashAntes = hash(usuario.getId());
        String novoHash = passwordEncoder.encode("novaSenhaComFalha123");

        // Falha real e isolada: constraint temporária rejeita o INSERT de auditoria.
        migratorJdbc.execute(
                "ALTER TABLE public.admin_audit_event "
                        + "ADD CONSTRAINT tmp_rejeita_password_change "
                        + "CHECK (operation <> 'PASSWORD_CHANGE') NOT VALID");
        try {
            assertThrows(DataAccessException.class,
                    () -> usuarioRepository.alterarSenha(usuario.getId(), hashAntes, novoHash));
        } finally {
            migratorJdbc.execute(
                    "ALTER TABLE public.admin_audit_event "
                            + "DROP CONSTRAINT IF EXISTS tmp_rejeita_password_change");
        }

        assertEquals(hashAntes, hash(usuario.getId()));
        assertEquals(0L, versao(usuario.getId()));

        Long eventos = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM public.admin_audit_event
                WHERE operation = 'PASSWORD_CHANGE' AND outcome = 'SUCCEEDED'
                  AND actor_user_id = ?
                """, Long.class, usuario.getId());
        assertEquals(0L, eventos != null ? eventos : 0L);

        assertEquals(200, login(usuario.getEmail(), SENHA_INICIAL).getResponse().getStatus());
        assertEquals(401, login(usuario.getEmail(), "novaSenhaComFalha123").getResponse().getStatus());
    }

    @Test
    void autorizacaoAdminPermaneceFuncionando() throws Exception {
        Long adminId = migratorJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Admin Senha', ?, '11999990000', ?, 'ADMIN')
                RETURNING id
                """,
                Long.class,
                emailUnico("admin_senha"),
                passwordEncoder.encode(SENHA_INICIAL)
        );
        Usuario admin = usuarioRepository.findById(adminId).orElseThrow();

        mockMvc.perform(get("/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk());
        assertEquals(0L, versao(adminId));
    }

    @Test
    void cincoSenhasIncorretasBloqueiamEAProximaRetorna429SemAlterarNada() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String ipFixo = ip();
        String hashAntes = hash(usuario.getId());

        for (int i = 0; i < 5; i++) {
            assertEquals(400,
                    patchSenha(usuario, ipFixo, "senhaErrada", "novaSenha123")
                            .getResponse().getStatus());
        }
        MvcResult bloqueada = patchSenha(usuario, ipFixo, "senhaErrada", "novaSenha123");

        assertEquals(429, bloqueada.getResponse().getStatus());
        assertEquals(
                RateLimitSource.PASSWORD_CHANGE_RATE_LIMIT.headerValue(),
                bloqueada.getResponse().getHeader(RateLimitSource.HEADER_NAME));
        long retryAfter = Long.parseLong(bloqueada.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(retryAfter >= 1L && retryAfter <= 60L,
                "Retry-After esperado entre 1 e 60 s, obtido: " + retryAfter);

        String corpo = bloqueada.getResponse().getContentAsString();
        JsonNode jsonErro = objectMapper.readTree(corpo);
        assertFalse(jsonErro.path("timestamp").asText().isBlank());
        assertEquals(429, jsonErro.path("status").asInt());
        assertEquals("Limite de requisições excedido. Tente novamente mais tarde.",
                jsonErro.path("error").asText());
        assertFalse(corpo.contains("senhaErrada"));
        assertFalse(corpo.contains("novaSenha123"));
        assertEquals(hashAntes, hash(usuario.getId()));
        assertEquals(0L, versao(usuario.getId()));

        // O token continua válido: a tentativa bloqueada não executou a alteração.
        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, token(usuario)))
                .andExpect(status().isOk());
    }

    @Test
    void contaBloqueadaComSenhaCorretaNaoExecutaAlteracao() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String ipFixo = ip();
        String hashAntes = hash(usuario.getId());

        for (int i = 0; i < 5; i++) {
            assertEquals(400,
                    patchSenha(usuario, ipFixo, "senhaErrada", "novaSenha123")
                            .getResponse().getStatus());
        }
        assertEquals(429,
                patchSenha(usuario, ipFixo, SENHA_INICIAL, "novaSenha123")
                        .getResponse().getStatus());

        assertEquals(hashAntes, hash(usuario.getId()));
        assertEquals(0L, versao(usuario.getId()));
    }

    @Test
    void limiteDeUmaContaNaoBloqueiaOutraConta() throws Exception {
        Usuario vitima = criarCliente(SENHA_INICIAL);
        Usuario outro = criarCliente(SENHA_INICIAL);
        String ipFixo = ip();

        for (int i = 0; i < 5; i++) {
            assertEquals(400,
                    patchSenha(vitima, ipFixo, "senhaErrada", "novaSenha123")
                            .getResponse().getStatus());
        }

        assertEquals(204,
                patchSenha(outro, ipFixo, SENHA_INICIAL, "novaSenhaOutro123")
                        .getResponse().getStatus());
        assertEquals(1L, versao(outro.getId()));
        assertEquals(0L, versao(vitima.getId()));
    }

    @Test
    void xForwardedForNaoConfiavelNaoContornaOLimite() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String ipFixo = ip();

        for (int i = 0; i < 5; i++) {
            MvcResult resultado = mockMvc.perform(patch("/usuarios/me/senha")
                            .header(HttpHeaders.AUTHORIZATION, token(usuario))
                            .header("X-Forwarded-For", "203.0.113." + (i + 1))
                            .with(request -> {
                                request.setRemoteAddr(ipFixo);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("senhaErrada", "novaSenha123", "novaSenha123")))
                    .andReturn();
            assertEquals(400, resultado.getResponse().getStatus());
        }
        MvcResult bloqueada = mockMvc.perform(patch("/usuarios/me/senha")
                        .header(HttpHeaders.AUTHORIZATION, token(usuario))
                        .header("X-Forwarded-For", "203.0.113.99")
                        .with(request -> {
                            request.setRemoteAddr(ipFixo);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("senhaErrada", "novaSenha123", "novaSenha123")))
                .andReturn();

        assertEquals(429, bloqueada.getResponse().getStatus());
        assertEquals(0L, versao(usuario.getId()));
    }

    @Test
    void limiteDeSenhaNaoConsomeLimiteDeLoginNemCausaBloqueioDeForcaBruta() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String ipFixo = ip();

        for (int i = 0; i < 5; i++) {
            assertEquals(400,
                    patchSenha(usuario, ipFixo, "senhaErrada", "novaSenha123")
                            .getResponse().getStatus());
        }

        // Login usa IP novo e não foi afetado pelas tentativas de alteração.
        assertEquals(200, login(usuario.getEmail(), SENHA_INICIAL).getResponse().getStatus());
    }

    @Test
    void tentativasConcorrentesNaoUltrapassamOLimite() throws Exception {
        Usuario usuario = criarCliente(SENHA_INICIAL);
        String ipFixo = ip();
        String hashAntes = hash(usuario.getId());
        String token = token(usuario);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch inicio = new CountDownLatch(1);
        AtomicInteger aceitas = new AtomicInteger();
        AtomicInteger bloqueadas = new AtomicInteger();
        try {
            var futuros = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) {
                futuros.add(executor.submit(() -> {
                    inicio.await();
                    MvcResult resultado = mockMvc.perform(patch("/usuarios/me/senha")
                                    .header(HttpHeaders.AUTHORIZATION, token)
                                    .with(request -> {
                                        request.setRemoteAddr(ipFixo);
                                        return request;
                                    })
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json("senhaErrada", "novaSenha123", "novaSenha123")))
                            .andReturn();
                    if (resultado.getResponse().getStatus() == 429) {
                        assertEquals(
                                RateLimitSource.PASSWORD_CHANGE_CONCURRENCY.headerValue(),
                                resultado.getResponse().getHeader(RateLimitSource.HEADER_NAME));
                        assertEquals("1", resultado.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
                        JsonNode json429 = objectMapper.readTree(resultado.getResponse().getContentAsString());
                        assertFalse(json429.path("timestamp").asText().isBlank());
                        assertEquals(429, json429.path("status").asInt());
                        assertEquals("Limite de requisições excedido. Tente novamente mais tarde.",
                                json429.path("error").asText());
                        bloqueadas.incrementAndGet();
                    } else if (resultado.getResponse().getStatus() == 400) {
                        aceitas.incrementAndGet();
                    }
                    return null;
                }));
            }
            inicio.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(5, aceitas.get(), "no máximo cinco senhas incorretas processadas");
        assertEquals(3, bloqueadas.get(), "as demais devem receber 429");
        assertEquals(hashAntes, hash(usuario.getId()));
        assertEquals(0L, versao(usuario.getId()));
    }
}
