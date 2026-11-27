package br.com.arena.exception;

import java.util.Objects;

/** V5: limite de requisições ou de tentativas de autenticação excedido. */
public class TooManyRequestsException extends RuntimeException {

    private final RateLimitSource source;
    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, RateLimitSource source, long retryAfterSeconds) {
        super(message);
        this.source = Objects.requireNonNull(source, "source não pode ser nulo");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    /**
     * Construtor legado preservado apenas para compatibilidade retroativa.
     * Novos chamadores de produção devem informar explicitamente {@link RateLimitSource}.
     */
    @Deprecated
    public TooManyRequestsException(String message) {
        super(message);
        this.source = null;
        this.retryAfterSeconds = 1L;
    }

    public RateLimitSource getSource() {
        return source;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
