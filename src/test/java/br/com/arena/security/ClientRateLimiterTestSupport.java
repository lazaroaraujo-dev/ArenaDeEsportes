package br.com.arena.security;

/**
 * Acessor exclusivo de testes para inspecionar contadores internos do {@link ClientRateLimiter}
 * fora do pacote {@code br.com.arena.security} sem ampliar a API pública de produção.
 */
public final class ClientRateLimiterTestSupport {

    private ClientRateLimiterTestSupport() {
    }

    public static int passwordInFlightCount(ClientRateLimiter limiter, long accountId) {
        return limiter.passwordInFlightCount(accountId);
    }

    public static int passwordFailureCount(ClientRateLimiter limiter, long accountId, String rawIp) {
        return limiter.passwordFailureCount(accountId, rawIp);
    }
}
