package br.com.arena.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record TestPostgresSettings(
        String url,
        String runtimeUsername,
        String runtimePassword,
        String migratorUsername,
        String migratorPassword,
        String writerUsername,
        String writerPassword,
        String offlineUsername,
        String offlinePassword,
        String jwtSecret,
        long jwtExpirationMillis,
        String h04InitialInstall) {

    private static final Pattern TEST_DATABASE_URL = Pattern.compile(
            "^jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/arena_flyway_test$"
    );

    public static TestPostgresSettings from(Map<String, String> environment) {
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

        String runtimeUsername = required(
                environment, "TEST_POSTGRES_RUNTIME_USERNAME");
        String runtimePassword = required(
                environment, "TEST_POSTGRES_RUNTIME_PASSWORD");
        String migratorUsername = required(
                environment, "TEST_POSTGRES_MIGRATOR_USERNAME");
        String migratorPassword = required(
                environment, "TEST_POSTGRES_MIGRATOR_PASSWORD");
        String writerUsername = required(
                environment, "TEST_POSTGRES_WRITER_USERNAME");
        String writerPassword = required(
                environment, "TEST_POSTGRES_WRITER_PASSWORD");
        String offlineUsername = required(
                environment, "TEST_POSTGRES_OFFLINE_USERNAME");
        String offlinePassword = required(
                environment, "TEST_POSTGRES_OFFLINE_PASSWORD");

        if (!"arena_runtime".equals(runtimeUsername)
                || !"arena_migrator".equals(migratorUsername)
                || !"arena_admin_writer".equals(writerUsername)
                || !"arena_offline_operator".equals(offlineUsername)) {
            throw new IllegalStateException(
                    "Test PostgreSQL roles must be arena_runtime, arena_migrator, arena_admin_writer and arena_offline_operator."
            );
        }

        if (runtimePassword.equals(migratorPassword)
                || runtimePassword.equals(writerPassword)
                || runtimePassword.equals(offlinePassword)
                || migratorPassword.equals(writerPassword)
                || migratorPassword.equals(offlinePassword)
                || writerPassword.equals(offlinePassword)) {
            throw new IllegalStateException(
                    "All four test PostgreSQL passwords must be distinct."
            );
        }

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

        String h04InitialInstall = environment.getOrDefault("TEST_H04_INITIAL_INSTALL", "false");
        if (!"true".equals(h04InitialInstall) && !"false".equals(h04InitialInstall)) {
            throw new IllegalStateException(
                    "TEST_H04_INITIAL_INSTALL must be 'true' or 'false'."
            );
        }

        return new TestPostgresSettings(
                url,
                runtimeUsername,
                runtimePassword,
                migratorUsername,
                migratorPassword,
                writerUsername,
                writerPassword,
                offlineUsername,
                offlinePassword,
                jwtSecret,
                expirationMillis,
                h04InitialInstall
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
