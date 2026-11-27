package br.com.arena.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Limites da V5. Todos os valores têm default seguro e podem ser sobrepostos
 * por propriedades externas; nenhum segredo é armazenado aqui.
 */
@Component
public class SecurityHardeningProperties {

    private final int loginRateLimit;
    private final int registerRateLimit;
    private final int passwordChangeRateLimit;
    private final int passwordChangeMaxConcurrent;
    private final long windowSeconds;
    private final boolean trustForwardedFor;
    private final String trustedProxies;
    private final int maxFailedLogins;
    private final long lockSeconds;
    private final int maxAccountFailedLogins;
    private final long accountLockSeconds;

    @Autowired
    public SecurityHardeningProperties(
            @Value("${v5.security.login-rate-limit:10}") int loginRateLimit,
            @Value("${v5.security.register-rate-limit:5}") int registerRateLimit,
            @Value("${v5.security.password-change-rate-limit:5}") int passwordChangeRateLimit,
            @Value("${v5.security.password-change-max-concurrent:5}") int passwordChangeMaxConcurrent,
            @Value("${v5.security.window-seconds:60}") long windowSeconds,
            @Value("${v5.security.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${v5.security.trusted-proxies:}") String trustedProxies,
            @Value("${v5.security.max-failed-logins:5}") int maxFailedLogins,
            @Value("${v5.security.lock-seconds:300}") long lockSeconds,
            @Value("${v5.security.max-account-failed-logins:20}") int maxAccountFailedLogins,
            @Value("${v5.security.account-lock-seconds:60}") long accountLockSeconds) {

        requirePositive(loginRateLimit, "v5.security.login-rate-limit");
        requirePositive(registerRateLimit, "v5.security.register-rate-limit");
        requirePositive(passwordChangeRateLimit, "v5.security.password-change-rate-limit");
        requirePositive(passwordChangeMaxConcurrent, "v5.security.password-change-max-concurrent");
        requirePositive(windowSeconds, "v5.security.window-seconds");
        requirePositive(maxFailedLogins, "v5.security.max-failed-logins");
        requirePositive(lockSeconds, "v5.security.lock-seconds");
        requirePositive(maxAccountFailedLogins, "v5.security.max-account-failed-logins");
        requirePositive(accountLockSeconds, "v5.security.account-lock-seconds");

        this.loginRateLimit = loginRateLimit;
        this.registerRateLimit = registerRateLimit;
        this.passwordChangeRateLimit = passwordChangeRateLimit;
        this.passwordChangeMaxConcurrent = passwordChangeMaxConcurrent;
        this.windowSeconds = windowSeconds;
        this.trustForwardedFor = trustForwardedFor;
        this.trustedProxies = trustedProxies == null ? "" : trustedProxies;
        this.maxFailedLogins = maxFailedLogins;
        this.lockSeconds = lockSeconds;
        this.maxAccountFailedLogins = maxAccountFailedLogins;
        this.accountLockSeconds = accountLockSeconds;
    }

    /** Construtor de teste compatível com a configuração anterior à V5.9.2.1. */
    public SecurityHardeningProperties(
            int loginRateLimit,
            int registerRateLimit,
            int passwordChangeRateLimit,
            long windowSeconds,
            boolean trustForwardedFor,
            String trustedProxies,
            int maxFailedLogins,
            long lockSeconds,
            int maxAccountFailedLogins,
            long accountLockSeconds) {
        this(loginRateLimit, registerRateLimit, passwordChangeRateLimit, 5, windowSeconds,
                trustForwardedFor, trustedProxies, maxFailedLogins, lockSeconds,
                maxAccountFailedLogins, accountLockSeconds);
    }

    /** Construtor de teste compatível com a configuração anterior à V5.7. */
    public SecurityHardeningProperties(
            int loginRateLimit,
            int registerRateLimit,
            long windowSeconds,
            boolean trustForwardedFor,
            int maxFailedLogins,
            long lockSeconds) {
        this(loginRateLimit, registerRateLimit, windowSeconds, trustForwardedFor, "",
                maxFailedLogins, lockSeconds, 20, 60);
    }

    /** Construtor de teste compatível com a configuração anterior à V5.9.2. */
    public SecurityHardeningProperties(
            int loginRateLimit,
            int registerRateLimit,
            long windowSeconds,
            boolean trustForwardedFor,
            String trustedProxies,
            int maxFailedLogins,
            long lockSeconds,
            int maxAccountFailedLogins,
            long accountLockSeconds) {
        this(loginRateLimit, registerRateLimit, 5, windowSeconds, trustForwardedFor,
                trustedProxies, maxFailedLogins, lockSeconds,
                maxAccountFailedLogins, accountLockSeconds);
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
    }

    public int loginRateLimit() {
        return loginRateLimit;
    }

    public int registerRateLimit() {
        return registerRateLimit;
    }

    public int passwordChangeRateLimit() {
        return passwordChangeRateLimit;
    }

    public int passwordChangeMaxConcurrent() {
        return passwordChangeMaxConcurrent;
    }

    public long windowMillis() {
        return windowSeconds * 1000L;
    }

    public boolean trustForwardedFor() {
        return trustForwardedFor;
    }

    public List<String> trustedProxies() {
        if (trustedProxies.isBlank()) {
            return List.of();
        }
        return Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public int maxFailedLogins() {
        return maxFailedLogins;
    }

    public long lockMillis() {
        return lockSeconds * 1000L;
    }

    public int maxAccountFailedLogins() {
        return maxAccountFailedLogins;
    }

    public long accountLockMillis() {
        return accountLockSeconds * 1000L;
    }

    public int limitFor(String bucket) {
        if ("password_change".equals(bucket)) {
            return passwordChangeRateLimit;
        }
        return "register".equals(bucket) ? registerRateLimit : loginRateLimit;
    }
}
