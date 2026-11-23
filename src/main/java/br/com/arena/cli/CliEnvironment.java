package br.com.arena.cli;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuração de ambiente do CLI offline. Carrega somente variáveis próprias
 * da role {@code arena_offline_operator}; nunca fallback para credenciais de
 * runtime, Flyway ou produção.
 */
public record CliEnvironment(
        String url,
        String username,
        String password,
        String operatorId,
        String operatorName) {

    private static final Pattern POSTGRES_URL = Pattern.compile(
            "^jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+$"
    );

    public static CliEnvironment from(Map<String, String> env) {
        String url = required(env, "OFFLINE_POSTGRES_URL");
        if (!POSTGRES_URL.matcher(url).matches()) {
            throw new IllegalStateException(
                    "OFFLINE_POSTGRES_URL must be a jdbc:postgresql://host/db URL."
            );
        }

        String username = required(env, "OFFLINE_POSTGRES_USERNAME");
        if (!"arena_offline_operator".equals(username)) {
            throw new IllegalStateException(
                    "OFFLINE_POSTGRES_USERNAME must be arena_offline_operator."
            );
        }

        String password = required(env, "OFFLINE_POSTGRES_PASSWORD");
        if (password.length() < 16) {
            throw new IllegalStateException(
                    "OFFLINE_POSTGRES_PASSWORD must contain at least 16 characters."
            );
        }

        String operatorId = required(env, "OFFLINE_OPERATOR_ID");

        String operatorName = env.getOrDefault("OFFLINE_OPERATOR_NAME", operatorId);

        return new CliEnvironment(url, username, password, operatorId, operatorName);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required offline environment variable " + name + " is missing."
            );
        }
        return value;
    }
}
