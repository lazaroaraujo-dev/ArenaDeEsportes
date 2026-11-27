package br.com.arena.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminWriterPropertiesTest {

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/arena_flyway_test";
    private static final String USERNAME = "arena_admin_writer";
    private static final String PASSWORD = "writer-password-123456";

    @Test
    void acceptsValidWriterConfiguration() {
        AdminWriterProperties properties = properties(URL, USERNAME, PASSWORD, 4, 1, 5000L);

        assertEquals(URL, properties.url());
        assertEquals(USERNAME, properties.username());
        assertEquals(PASSWORD, properties.password());
        assertEquals(4, properties.maximumPoolSize());
        assertEquals(1, properties.minimumIdle());
        assertEquals(5000L, properties.connectionTimeoutMs());
    }

    @Test
    void acceptsUrlWithQueryParameters() {
        AdminWriterProperties properties = properties(
                URL + "?sslmode=require",
                USERNAME,
                PASSWORD,
                4,
                1,
                5000L
        );

        assertTrue(properties.url().endsWith("?sslmode=require"));
    }

    @Test
    void rejectsMissingUrl() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> properties(" ", USERNAME, PASSWORD, 4, 1, 5000L)
        );

        assertTrue(error.getMessage().contains("arena.admin-writer.url"));
    }

    @Test
    void rejectsNonPostgresUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties("jdbc:h2:mem:test", USERNAME, PASSWORD, 4, 1, 5000L)
        );
    }

    @Test
    void rejectsRuntimeOrMigratorUsername() {
        for (String forbidden : new String[]{"arena_runtime", "arena_migrator", "arena_offline_operator"}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> properties(URL, forbidden, PASSWORD, 4, 1, 5000L)
            );

            assertTrue(error.getMessage().contains("arena_admin_writer"));
        }
    }

    @Test
    void rejectsMissingOrShortPassword() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, null, 4, 1, 5000L)
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, "curta", 4, 1, 5000L)
        );

        assertTrue(error.getMessage().contains("at least 16 characters"));
    }

    @Test
    void rejectsInvalidPoolConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, PASSWORD, 0, 0, 5000L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, PASSWORD, 17, 1, 5000L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, PASSWORD, 4, -1, 5000L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, PASSWORD, 4, 5, 5000L)
        );
    }

    @Test
    void rejectsInvalidConnectionTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, PASSWORD, 4, 1, 249L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URL, USERNAME, PASSWORD, 4, 1, 60_001L)
        );
    }

    private static AdminWriterProperties properties(
            String url,
            String username,
            String password,
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeoutMs) {
        return new AdminWriterProperties(
                url,
                username,
                password,
                maximumPoolSize,
                minimumIdle,
                connectionTimeoutMs
        );
    }
}
