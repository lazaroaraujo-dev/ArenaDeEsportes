package br.com.arena.exception;

/**
 * Conjunto fechado de origens válidas para respostas HTTP 429 ({@code X-Rate-Limit-Source}).
 *
 * <p>Os valores identificam apenas a camada de proteção acionada, sem expor
 * existência de conta, chaves internas ou contadores.</p>
 */
public enum RateLimitSource {

    REQUEST_RATE_LIMIT("request_rate_limit"),
    LOGIN_LOCKOUT("login_lockout"),
    PASSWORD_CHANGE_CONCURRENCY("password_change_concurrency"),
    PASSWORD_CHANGE_RATE_LIMIT("password_change_rate_limit");

    public static final String HEADER_NAME = "X-Rate-Limit-Source";

    private final String headerValue;

    RateLimitSource(String headerValue) {
        this.headerValue = headerValue;
    }

    public String headerValue() {
        return headerValue;
    }
}
