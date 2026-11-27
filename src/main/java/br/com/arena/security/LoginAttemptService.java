package br.com.arena.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Proteção contra força bruta em duas camadas complementares:
 *
 * <ul>
 *   <li>por (IP, hash do e-mail): limite existente do V5.5, bloqueio de 5 falhas
 *       por 300 s;</li>
 *   <li>por hash do e-mail: camada adicional da V5.7 para reduzir ataques
 *       distribuídos entre IPs, com limite maior e bloqueio curto.</li>
 * </ul>
 *
 * <p>Todo o estado é alterado dentro de operações atômicas por chave do
 * {@link ConcurrentHashMap} ({@code compute}/{@code computeIfPresent}); não há
 * monitor de {@code Attempt} nem chamada ao mapa dentro de bloco sincronizado.
 * Nenhuma senha, JWT ou e-mail em texto puro é armazenado.</p>
 */
@Component
public class LoginAttemptService {

    private final SecurityHardeningProperties properties;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attempt> accountAttempts = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepAt = new AtomicLong();

    @Autowired
    public LoginAttemptService(SecurityHardeningProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    LoginAttemptService(SecurityHardeningProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isLocked(String clientIp, String email) {
        return checkLock(clientIp, email).locked();
    }

    /**
     * Verifica bloqueio nas camadas (IP, hash do e-mail) e (hash do e-mail),
     * removendo bloqueios expirados de forma atômica por chave e calculando
     * o maior tempo restante em segundos ({@code >= 1} quando bloqueado).
     */
    public LockCheckResult checkLock(String clientIp, String email) {
        long now = clock.getAsLong();
        boolean[] ipLocked = new boolean[]{false};
        boolean[] accountLocked = new boolean[]{false};
        long[] ipRemainingMs = new long[]{0L};
        long[] accountRemainingMs = new long[]{0L};

        attempts.computeIfPresent(key(clientIp, email), (ignored, attempt) -> {
            if (attempt.lockUntil > 0 && attempt.lockUntil <= now) {
                return null;
            }
            if (attempt.lockUntil > now) {
                ipLocked[0] = true;
                ipRemainingMs[0] = attempt.lockUntil - now;
            }
            attempt.lastActivityAt = now;
            return attempt;
        });

        accountAttempts.computeIfPresent(accountKey(email), (ignored, attempt) -> {
            if (attempt.lockUntil > 0 && attempt.lockUntil <= now) {
                return null;
            }
            if (attempt.lockUntil > now) {
                accountLocked[0] = true;
                accountRemainingMs[0] = attempt.lockUntil - now;
            }
            attempt.lastActivityAt = now;
            return attempt;
        });

        maybeSweep(now);
        boolean locked = ipLocked[0] || accountLocked[0];
        long maxRemainingMs = Math.max(ipRemainingMs[0], accountRemainingMs[0]);
        long retryAfterSeconds = locked ? Math.max(1L, (maxRemainingMs + 999L) / 1000L) : 0L;
        return new LockCheckResult(locked, retryAfterSeconds);
    }

    public record LockCheckResult(boolean locked, long retryAfterSeconds) {
    }

    public void recordFailure(String clientIp, String email) {
        long now = clock.getAsLong();

        attempts.compute(key(clientIp, email), (ignored, existing) ->
                registerFailure(existing, now,
                        properties.maxFailedLogins(), properties.lockMillis()));

        accountAttempts.compute(accountKey(email), (ignored, existing) ->
                registerFailure(existing, now,
                        properties.maxAccountFailedLogins(), properties.accountLockMillis()));

        maybeSweep(now);
    }

    public void recordSuccess(String clientIp, String email) {
        remove(clientIp, email);
    }

    public void reset(String clientIp, String email) {
        remove(clientIp, email);
    }

    /** Visível para testes: quantidade de chaves IP+conta retidas. */
    int trackedAttempts() {
        return attempts.size();
    }

    /** Visível para testes: quantidade de chaves por conta retidas. */
    int trackedAccountAttempts() {
        return accountAttempts.size();
    }

    /** Visível para testes: confirma que o e-mail não aparece em texto puro. */
    String keyFor(String clientIp, String email) {
        return key(clientIp, email);
    }

    /** Visível para testes: confirma que a chave da conta também é apenas hash. */
    String accountKeyFor(String email) {
        return accountKey(email);
    }

    private static Attempt registerFailure(
            Attempt existing, long now, int maxFailures, long lockMillis) {
        Attempt attempt = existing == null ? new Attempt(now) : existing;
        attempt.lastActivityAt = now;

        if (attempt.lockUntil > 0) {
            if (attempt.lockUntil > now) {
                return attempt;
            }
            attempt.lockUntil = 0;
            attempt.failures = 0;
        }

        attempt.failures++;
        if (attempt.failures >= maxFailures) {
            attempt.lockUntil = now + lockMillis;
            attempt.failures = 0;
        }
        return attempt;
    }

    private void remove(String clientIp, String email) {
        attempts.remove(key(clientIp, email));
        accountAttempts.remove(accountKey(email));
    }

    private void maybeSweep(long now) {
        long last = lastSweepAt.get();
        if (now - last >= properties.lockMillis()
                && lastSweepAt.compareAndSet(last, now)) {
            purgeExpired(attempts, properties.lockMillis(), now);
            purgeExpired(accountAttempts, properties.accountLockMillis(), now);
        }
    }

    private static void purgeExpired(
            ConcurrentHashMap<String, Attempt> map, long retentionMillis, long now) {
        long cutoff = now - retentionMillis;
        for (String key : map.keySet()) {
            map.computeIfPresent(key, (ignored, attempt) -> {
                boolean lockExpired = attempt.lockUntil > 0 && attempt.lockUntil <= now;
                boolean inactive = attempt.lastActivityAt < cutoff;
                return (lockExpired || inactive) ? null : attempt;
            });
        }
    }

    private static String key(String clientIp, String email) {
        return (clientIp == null ? "unknown" : clientIp) + "|" + accountKey(email);
    }

    private static String accountKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the JVM.", ex);
        }
    }

    private static final class Attempt {
        private int failures;
        private long lockUntil;
        private long lastActivityAt;

        private Attempt(long createdAt) {
            this.lastActivityAt = createdAt;
        }
    }
}
