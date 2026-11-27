package br.com.arena.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * Configuração do datasource exclusivo de {@code arena_admin_writer}.
 *
 * <p>Não existe fallback para credenciais de runtime, Flyway ou operador
 * offline. Qualquer valor ausente, vazio ou incompatível interrompe a
 * inicialização da aplicação.</p>
 */
@ConfigurationProperties(prefix = "arena.admin-writer")
public record AdminWriterProperties(
        String url,
        String username,
        String password,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs
) {

    private static final Pattern POSTGRES_URL = Pattern.compile(
            "^jdbc:postgresql://[^/\\s]+/[a-zA-Z0-9_]+(\\?\\S*)?$"
    );
    private static final String REQUIRED_USERNAME = "arena_admin_writer";
    private static final int MINIMUM_PASSWORD_LENGTH = 16;
    private static final int MAXIMUM_POOL_SIZE_LIMIT = 16;
    private static final long MINIMUM_CONNECTION_TIMEOUT_MS = 250L;
    private static final long MAXIMUM_CONNECTION_TIMEOUT_MS = 60_000L;

    public AdminWriterProperties {
        url = requireNonBlank(url, "arena.admin-writer.url");
        if (!POSTGRES_URL.matcher(url).matches()) {
            throw new IllegalArgumentException(
                    "arena.admin-writer.url must be a jdbc:postgresql://host/database URL."
            );
        }

        username = requireNonBlank(username, "arena.admin-writer.username");
        if (!REQUIRED_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "arena.admin-writer.username must be " + REQUIRED_USERNAME + "."
            );
        }

        password = requireNonBlank(password, "arena.admin-writer.password");
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "arena.admin-writer.password must contain at least "
                            + MINIMUM_PASSWORD_LENGTH + " characters."
            );
        }

        if (maximumPoolSize < 1 || maximumPoolSize > MAXIMUM_POOL_SIZE_LIMIT) {
            throw new IllegalArgumentException(
                    "arena.admin-writer.maximum-pool-size must be between 1 and "
                            + MAXIMUM_POOL_SIZE_LIMIT + "."
            );
        }

        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "arena.admin-writer.minimum-idle must be between 0 and maximum-pool-size."
            );
        }

        if (connectionTimeoutMs < MINIMUM_CONNECTION_TIMEOUT_MS
                || connectionTimeoutMs > MAXIMUM_CONNECTION_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "arena.admin-writer.connection-timeout-ms must be between "
                            + MINIMUM_CONNECTION_TIMEOUT_MS + " and "
                            + MAXIMUM_CONNECTION_TIMEOUT_MS + "."
            );
        }
    }

    private static String requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required.");
        }
        return value;
    }
}
