package br.com.arena.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record TestPostgresSettings(
        String url,
        String username,
        String password,
        String jwtSecret,
        long jwtExpirationMillis) {

    private static final Pattern TEST_DATABASE_URL = Pattern.compile(
            "^jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/arena_flyway_test$"
    );

    static TestPostgresSettings from(Map<String, String> environment) {
        String url = required(environment, "TEST_POSTGRES_URL");
        Matcher matcher = TEST_DATABASE_URL.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "TEST_POSTGRES_URL must target 127.0.0.1:<port>/arena_flyway_test."
            );
        }

        int port;
        try {
            port = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("TEST_POSTGRES_URL has an invalid port.");
        }
        if (port > 65_535) {
            throw new IllegalStateException("TEST_POSTGRES_URL has an invalid port.");
        }

        String username = required(environment, "TEST_POSTGRES_USERNAME");
        String password = required(environment, "TEST_POSTGRES_PASSWORD");
        String jwtSecret = required(environment, "TEST_JWT_SECRET");
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("TEST_JWT_SECRET must contain at least 32 bytes.");
        }

        String expiration = required(environment, "TEST_JWT_EXPIRATION_MS");
        long expirationMillis;
        try {
            expirationMillis = Long.parseLong(expiration);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(
                    "TEST_JWT_EXPIRATION_MS must be a positive integer."
            );
        }
        if (expirationMillis <= 0) {
            throw new IllegalStateException(
                    "TEST_JWT_EXPIRATION_MS must be a positive integer."
            );
        }

        return new TestPostgresSettings(
                url,
                username,
                password,
                jwtSecret,
                expirationMillis
        );
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required test environment variable " + name + " is missing."
            );
        }
        return value;
    }
}
