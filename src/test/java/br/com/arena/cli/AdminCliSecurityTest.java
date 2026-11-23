package br.com.arena.cli;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCliSecurityTest {

    @Test
    void cliEnvironmentRequiresOfflineOperatorRole() {
        Map<String, String> env = Map.of(
                "OFFLINE_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/arena",
                "OFFLINE_POSTGRES_USERNAME", "arena_runtime",
                "OFFLINE_POSTGRES_PASSWORD", "runtimepassword123456"
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> CliEnvironment.from(env)
        );

        assertTrue(error.getMessage().contains("arena_offline_operator"));
    }

    @Test
    void cliEnvironmentRejectsShortPassword() {
        Map<String, String> env = Map.of(
                "OFFLINE_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/arena",
                "OFFLINE_POSTGRES_USERNAME", "arena_offline_operator",
                "OFFLINE_POSTGRES_PASSWORD", "curto"
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> CliEnvironment.from(env)
        );

        assertTrue(error.getMessage().contains("16 characters"));
    }

    @Test
    void cliDoesNotPrintPasswordInOutput() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(captured));

        try {
            StaticInputReader input = new StaticInputReader()
                    .add("Admin Bootstrap")
                    .add("bootstrap@example.invalid")
                    .add("0000000000")
                    .addPassword("senhaSegura123!")
                    .addPassword("senhaSegura123!");

            CliEnvironment env = new CliEnvironment(
                    "jdbc:postgresql://127.0.0.1:59999/arena",
                    "arena_offline_operator",
                    "offlinepassword123456",
                    "test-operator",
                    "Test Operator"
            );

            AdminLifecycleCliService mockService = new AdminLifecycleCliService(env) {
                @Override
                public boolean v3FunctionsExist() {
                    return true;
                }

                @Override
                public Long bootstrapFirstAdmin(String nome, String email, String telefone, String senhaHash, String actorRef) {
                    return 42L;
                }
            };

            CliRunner runner = new CliRunner(env, input, mockService);
            int exitCode = runner.execute("bootstrap");

            assertEquals(CliRunner.EXIT_SUCCESS, exitCode);
            String output = captured.toString();
            assertTrue(!output.contains("senhaSegura123!"), "Plain password must not appear in CLI output");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void adminCliApplicationExcludesWebDataSourceAndJpaAutoConfigurations() {
        EnableAutoConfiguration annotation = AdminCliApplication.class
                .getAnnotation(EnableAutoConfiguration.class);
        assertNotNull(annotation);

        List<String> excluded = Arrays.asList(annotation.excludeName());
        assertTrue(excluded.contains(
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"));
        assertTrue(excluded.contains(
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"));
        assertTrue(excluded.contains(
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"));
    }
}
