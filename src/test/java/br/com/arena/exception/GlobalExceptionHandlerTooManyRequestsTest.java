package br.com.arena.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTooManyRequestsTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void conjuntoDeOrigensRateLimitSourceEhEstritamenteFechado() {
        Set<String> valores = Arrays.stream(RateLimitSource.values())
                .map(RateLimitSource::headerValue)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "request_rate_limit",
                "login_lockout",
                "password_change_concurrency",
                "password_change_rate_limit"
        ), valores);
        assertFalse(valores.contains("rate_limit"));
    }

    @Test
    void construtorTipadoExigeSourceNaoNuloEGaranteRetryAfterMinimoUm() {
        assertThrows(NullPointerException.class,
                () -> new TooManyRequestsException("msg", null, 5L));

        TooManyRequestsException ex = new TooManyRequestsException(
                "msg", RateLimitSource.LOGIN_LOCKOUT, 0L);
        assertEquals(RateLimitSource.LOGIN_LOCKOUT, ex.getSource());
        assertEquals(1L, ex.getRetryAfterSeconds());
    }

    @Test
    void tratarLimiteExcedidoEmiteHeadersValidoseMensagemGenerica() throws Exception {
        for (RateLimitSource source : RateLimitSource.values()) {
            TooManyRequestsException ex = new TooManyRequestsException(
                    "mensagem interna sensivel", source, 42L);
            ResponseEntity<ErrorResponse> response = handler.tratarLimiteExcedido(ex);

            assertEquals(429, response.getStatusCode().value());
            assertEquals("42", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            assertEquals(source.headerValue(),
                    response.getHeaders().getFirst(RateLimitSource.HEADER_NAME));

            ErrorResponse body = response.getBody();
            assertNotNull(body);
            assertNotNull(body.getTimestamp());
            assertEquals(429, body.getStatus());
            assertEquals("Limite de requisições excedido. Tente novamente mais tarde.",
                    body.getError());

            String json = objectMapper.findAndRegisterModules().writeValueAsString(body);
            assertFalse(json.contains("mensagem interna sensivel"));
            assertTrue(json.contains("timestamp"));
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void construtorLegadoNaoEmiteRateLimitInvalidoNemValorForaDoContrato() {
        TooManyRequestsException legado = new TooManyRequestsException("legado");
        ResponseEntity<ErrorResponse> response = handler.tratarLimiteExcedido(legado);

        assertEquals(429, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNull(response.getHeaders().getFirst(RateLimitSource.HEADER_NAME));
        assertNotNull(response.getBody());
        assertEquals("Limite de requisições excedido. Tente novamente mais tarde.",
                response.getBody().getError());
    }
}
