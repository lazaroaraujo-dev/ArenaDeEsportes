package br.com.arena.security;

import br.com.arena.exception.RateLimitSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** V5.6/A2: variações de caminho não podem escapar do rate limiting. */
class RateLimitFilterTest {

    private static SecurityHardeningProperties properties() {
        // Limite 1 por bucket facilita provar que duas formas consomem o mesmo contador.
        return new SecurityHardeningProperties(1, 1, 60, false, 5, 300);
    }

    private static RateLimitFilter filter() {
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), () -> 1_000L);
        return new RateLimitFilter(limiter, new ClientIdentityResolver(properties()));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("192.0.2.10");
        return request;
    }

    private static MockFilterChain perform(RateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void rotaNormalConsomeContadorERetorna429AoExceder() throws Exception {
        RateLimitFilter filter = filter();

        assertNotNull(perform(filter, request("POST", "/auth/login")).getRequest());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/auth/login"), response, new MockFilterChain());
        assertEquals(429, response.getStatus());
    }

    @Test
    void parametroDeMatrizNaoBurlaORateLimit() throws Exception {
        RateLimitFilter filter = filter();

        assertNotNull(perform(filter, request("POST", "/auth/login;x=1")).getRequest());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/auth/login"), response, new MockFilterChain());
        assertEquals(429, response.getStatus());
    }

    @Test
    void segmentoPercentualmenteCodificadoNaoBurlaORateLimit() throws Exception {
        RateLimitFilter filter = filter();

        assertNotNull(perform(filter, request("POST", "/auth/%6cogin")).getRequest());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/auth/login"), response, new MockFilterChain());
        assertEquals(429, response.getStatus());
    }

    @Test
    void contextPathNaoBurlaORateLimit() throws Exception {
        RateLimitFilter filter = filter();

        MockHttpServletRequest contextual = request("POST", "/arena/auth/login");
        contextual.setContextPath("/arena");
        assertNotNull(perform(filter, contextual).getRequest());

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/auth/login"), response, new MockFilterChain());
        assertEquals(429, response.getStatus());
    }

    @Test
    void barraFinalNaoConsomeContadorDeOutraRota() throws Exception {
        RateLimitFilter filter = filter();

        // Rota com barra final não é equivalente no MVC e não deve consumir o bucket.
        assertNotNull(perform(filter, request("POST", "/auth/login/")).getRequest());
        assertNotNull(perform(filter, request("POST", "/auth/login")).getRequest());
    }

    @Test
    void rotaDiferenteNaoConsomeContador() throws Exception {
        RateLimitFilter filter = filter();

        assertNotNull(perform(filter, request("POST", "/auth/outro")).getRequest());
        assertNotNull(perform(filter, request("POST", "/auth/login")).getRequest());
    }

    @Test
    void metodoDiferenteNaoConsomeContador() throws Exception {
        RateLimitFilter filter = filter();

        assertNotNull(perform(filter, request("GET", "/auth/login")).getRequest());
        assertNotNull(perform(filter, request("POST", "/auth/login")).getRequest());
    }

    @Test
    void caminhoMalformadoNaoConsomeContador() throws Exception {
        RateLimitFilter filter = filter();

        MockFilterChain chain = perform(filter, request("POST", "/auth/%ZZlogin"));
        assertNotNull(chain.getRequest());
        assertNotNull(perform(filter, request("POST", "/auth/login")).getRequest());
    }

    @Test
    void cadastroPossuiContadorIndependente() throws Exception {
        RateLimitFilter filter = filter();

        assertNotNull(perform(filter, request("POST", "/auth/login")).getRequest());
        assertNotNull(perform(filter, request("POST", "/usuarios")).getRequest());
    }

    @Test
    void resposta429PadronizaContratoParaLoginECadastroComRelogioControlado() throws Exception {
        AtomicLong clock = new AtomicLong(1_000L);
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), clock::get);
        RateLimitFilter filter = new RateLimitFilter(limiter, new ClientIdentityResolver(properties()));
        ObjectMapper mapper = new ObjectMapper();

        assertNotNull(perform(filter, request("POST", "/auth/login")).getRequest());
        assertNotNull(perform(filter, request("POST", "/usuarios")).getRequest());

        clock.set(16_000L); // 15 s decorridos na janela de 60 s -> faltam 45 s
        for (String endpoint : new String[]{"/auth/login", "/usuarios"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request("POST", endpoint), response, new MockFilterChain());

            assertEquals(429, response.getStatus());
            assertEquals("45", response.getHeader(HttpHeaders.RETRY_AFTER));
            assertEquals(
                    RateLimitSource.REQUEST_RATE_LIMIT.headerValue(),
                    response.getHeader(RateLimitSource.HEADER_NAME));

            JsonNode body = mapper.readTree(response.getContentAsString());
            assertFalse(body.path("timestamp").asText().isBlank());
            assertNotNull(LocalDateTime.parse(body.path("timestamp").asText()));
            assertEquals(429, body.path("status").asInt());
            assertEquals("Limite de requisições excedido. Tente novamente mais tarde.",
                    body.path("error").asText());
        }
    }
}
