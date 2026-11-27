package br.com.arena.controller;

import br.com.arena.exception.RateLimitSource;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V5 — rate limiting, proteção contra força bruta e mensagens genéricas de login. */
@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class AuthSecurityV5IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String SENHA = "senha123!";
    private static final String MENSAGEM_401 = "E-mail ou senha inválidos.";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String email;

    @BeforeEach
    void criarCliente() {
        email = "v5_" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.invalid";
        usuarioRepository.registrarCliente(
                "Cliente V5", email, "11988887777", passwordEncoder.encode(SENHA));
    }

    private MockHttpServletRequestBuilder login(String ip, String requestEmail, String senha) {
        return post("/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + requestEmail + "\",\"senha\":\"" + senha + "\"}");
    }

    @Test
    void loginValidoRetornaToken() throws Exception {
        String body = mockMvc.perform(login("192.0.2.10", email, SENHA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        assertTrue(json.path("token").asText().length() > 20);
    }

    @Test
    void credenciaisInvalidasNaoRevelamSeEmailExiste() throws Exception {
        String mensagemEmailInexistente = mockMvc.perform(
                        login("192.0.2.20", "inexistente@exemplo.invalid", SENHA))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String mensagemSenhaErrada = mockMvc.perform(login("192.0.2.20", email, "senha-errada"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals(
                objectMapper.readTree(mensagemEmailInexistente).path("error").asText(),
                objectMapper.readTree(mensagemSenhaErrada).path("error").asText()
        );
        assertEquals(MENSAGEM_401,
                objectMapper.readTree(mensagemSenhaErrada).path("error").asText());
    }

    @Test
    void tentativasExcedidasRetornam429() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login("192.0.2.30", email, "senha-errada"))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult result = mockMvc.perform(login("192.0.2.30", email, SENHA))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(
                        RateLimitSource.HEADER_NAME,
                        RateLimitSource.LOGIN_LOCKOUT.headerValue()))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error")
                        .value("Limite de requisições excedido. Tente novamente mais tarde."))
                .andReturn();

        long retryAfter = Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(retryAfter >= 1L && retryAfter <= 300L,
                "Retry-After de login_lockout deve estar entre 1 e 300 s, obtido: " + retryAfter);
    }

    @Test
    void rateLimitGeralRetorna429AposLimite() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("192.0.2.40", email, SENHA))
                    .andExpect(status().isOk());
        }

        MvcResult result = mockMvc.perform(login("192.0.2.40", email, SENHA))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(
                        RateLimitSource.HEADER_NAME,
                        RateLimitSource.REQUEST_RATE_LIMIT.headerValue()))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error")
                        .value("Limite de requisições excedido. Tente novamente mais tarde."))
                .andReturn();

        long retryAfter = Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(retryAfter >= 1L && retryAfter <= 60L,
                "Retry-After de request_rate_limit deve estar entre 1 e 60 s, obtido: " + retryAfter);
    }

    @Test
    void cadastroRateLimitRetorna429Padronizado() throws Exception {
        String ipCadastro = "192.0.2.70";
        for (int i = 0; i < 5; i++) {
            String sufixo = UUID.randomUUID().toString().substring(0, 8);
            mockMvc.perform(post("/usuarios")
                            .with(request -> {
                                request.setRemoteAddr(ipCadastro);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                        "nome": "Cadastro RL %s",
                                        "email": "rl_%s@exemplo.invalid",
                                        "telefone": "11988887777",
                                        "senha": "senhaSegura123!"
                                    }
                                    """.formatted(sufixo, sufixo)))
                    .andExpect(status().isCreated());
        }

        MvcResult bloqueada = mockMvc.perform(post("/usuarios")
                        .with(request -> {
                            request.setRemoteAddr(ipCadastro);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nome": "Cadastro Excedente",
                                    "email": "excedente@exemplo.invalid",
                                    "telefone": "11988887777",
                                    "senha": "senhaSegura123!"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(
                        RateLimitSource.HEADER_NAME,
                        RateLimitSource.REQUEST_RATE_LIMIT.headerValue()))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error")
                        .value("Limite de requisições excedido. Tente novamente mais tarde."))
                .andReturn();

        long retryAfter = Long.parseLong(bloqueada.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(retryAfter >= 1L && retryAfter <= 60L,
                "Retry-After do filtro de cadastro deve estar entre 1 e 60 s, obtido: " + retryAfter);
    }

    @Test
    void lockoutDeContaExistenteEInexistenteRetornamMesmaResposta429() throws Exception {
        String ipExistente = "192.0.2.60";
        String ipInexistente = "192.0.2.61";
        String emailInexistente = "nao_existe_" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.invalid";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login(ipExistente, email, "senha-errada"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(login(ipInexistente, emailInexistente, "senha-errada"))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult respExistente = mockMvc.perform(login(ipExistente, email, "senha-errada"))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        MvcResult respInexistente = mockMvc.perform(login(ipInexistente, emailInexistente, "senha-errada"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertEquals(
                RateLimitSource.LOGIN_LOCKOUT.headerValue(),
                respExistente.getResponse().getHeader(RateLimitSource.HEADER_NAME));
        assertEquals(
                respExistente.getResponse().getHeader(RateLimitSource.HEADER_NAME),
                respInexistente.getResponse().getHeader(RateLimitSource.HEADER_NAME));

        JsonNode jsonExistente = objectMapper.readTree(respExistente.getResponse().getContentAsString());
        JsonNode jsonInexistente = objectMapper.readTree(respInexistente.getResponse().getContentAsString());
        assertEquals(jsonExistente.path("status").asInt(), jsonInexistente.path("status").asInt());
        assertEquals(jsonExistente.path("error").asText(), jsonInexistente.path("error").asText());
        assertFalse(jsonExistente.path("timestamp").asText().isBlank());
        assertFalse(jsonInexistente.path("timestamp").asText().isBlank());
    }

    @Test
    void xForwardedForForjadoNaoBurlaORateLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("192.0.2.50", email, SENHA)
                            .header("X-Forwarded-For", "198.51.100." + (i + 1)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(login("192.0.2.50", email, SENHA)
                        .header("X-Forwarded-For", "198.51.100.99"))
                .andExpect(status().isTooManyRequests());
    }
}
