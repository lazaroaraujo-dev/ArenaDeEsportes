package br.com.arena.cli;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

import org.springframework.jdbc.datasource.init.ScriptUtils;

import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IsolatedPostgresIntegrationTest
class AdminCliBootstrapIntegrationTest {

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetToPendingState() throws SQLException {
        executeSetup("""
                TRUNCATE TABLE public.usuario CASCADE;
                TRUNCATE TABLE public.quadra CASCADE;
                TRUNCATE TABLE public.admin_audit_event;
                UPDATE public.admin_lifecycle_state
                SET admin_count = 0,
                    bootstrap_consumed = false,
                    bootstrap_consumed_at = NULL,
                    recovery_required = false,
                    last_recovery_at = NULL,
                    updated_at = statement_timestamp()
                WHERE singleton_id = 1;
                """);
    }

    private void executeSetup(String sql) throws SQLException {
        try (java.sql.Connection connection = migratorDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new org.springframework.core.io.ByteArrayResource(("""
                            ALTER TABLE public.usuario DISABLE TRIGGER trg_h04_validate_admin_lifecycle_state;
                            """ + sql + """
                            ALTER TABLE public.usuario ENABLE TRIGGER trg_h04_validate_admin_lifecycle_state;
                            """).getBytes(StandardCharsets.UTF_8))
            );
        }
    }

    @Test
    void bootstrapSucceedsInPendingState() {
        StaticInputReader input = new StaticInputReader()
                .add("Admin Bootstrap")
                .add("bootstrap@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("bootstrap");

        assertEquals(CliRunner.EXIT_SUCCESS, exitCode);

        Map<String, Object> state = migratorJdbc().queryForMap(
                "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        assertEquals(true, state.get("bootstrap_consumed"));
        assertEquals(false, state.get("recovery_required"));
        assertEquals(1L, ((Number) state.get("admin_count")).longValue());

        Long auditCount = migratorJdbc().queryForObject(
                "SELECT COUNT(*) FROM public.admin_audit_event WHERE operation = 'BOOTSTRAP'",
                Long.class
        );
        assertEquals(1L, auditCount);

        String actorRef = migratorJdbc().queryForObject(
                "SELECT actor_ref FROM public.admin_audit_event WHERE operation = 'BOOTSTRAP'",
                String.class
        );
        assertEquals("test-operator", actorRef);

        Long adminCount = migratorJdbc().queryForObject(
                "SELECT COUNT(*) FROM public.usuario WHERE perfil = 'ADMIN'",
                Long.class
        );
        assertEquals(1L, adminCount);
    }

    @Test
    void secondBootstrapIsRejected() {
        bootstrapSucceedsInPendingState();

        StaticInputReader input = new StaticInputReader()
                .add("Admin Bootstrap")
                .add("bootstrap2@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("bootstrap");

        assertEquals(CliRunner.EXIT_BUSINESS_ERROR, exitCode);
        assertEquals(1L, countAdmins());
    }

    @Test
    void bootstrapRejectedInRecoveryRequiredState() throws SQLException {
        setRecoveryRequiredState();

        StaticInputReader input = new StaticInputReader()
                .add("Admin Bootstrap")
                .add("bootstrap@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        int exitCode;

        // O CliRunner cria seu PrintWriter a partir de System.out no construtor,
        // portanto a captura precisa ser instalada antes de instanciá-lo.
        try (PrintStream capture = new PrintStream(capturedOutput, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            System.setErr(capture);
            CliRunner runner = new CliRunner(offlineEnvironment(), input);
            exitCode = runner.execute("bootstrap");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String output = capturedOutput.toString(StandardCharsets.UTF_8);
        assertEquals(CliRunner.EXIT_BUSINESS_ERROR, exitCode);
        assertTrue(output.contains("Bootstrap rejeitado:"),
                "A saída deve conter o prefixo de rejeição do bootstrap. Saída: " + output);
        assertTrue(output.contains("H-04 cannot bootstrap while recovery is required"),
                "A mensagem real da função SQL deve aparecer na saída do CLI. Saída: " + output);
        assertEquals(0L, countAdmins());
    }

    @Test
    void bootstrapRejectsShortPassword() {
        StaticInputReader input = new StaticInputReader()
                .add("Admin Bootstrap")
                .add("bootstrap@example.invalid")
                .add("0000000000")
                .addPassword("curta123")
                .addPassword("curta123");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("bootstrap");

        assertEquals(CliRunner.EXIT_VALIDATION_ERROR, exitCode);
        assertEquals(0L, countAdmins());
    }

    @Test
    void bootstrapRejectsMismatchedPasswords() {
        StaticInputReader input = new StaticInputReader()
                .add("Admin Bootstrap")
                .add("bootstrap@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("outraSenha123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("bootstrap");

        assertEquals(CliRunner.EXIT_VALIDATION_ERROR, exitCode);
        assertEquals(0L, countAdmins());
    }

    private void setRecoveryRequiredState() throws SQLException {
        try (java.sql.Connection connection = migratorDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new org.springframework.core.io.ByteArrayResource("""
                            TRUNCATE TABLE public.usuario CASCADE;
                            TRUNCATE TABLE public.quadra CASCADE;
                            TRUNCATE TABLE public.admin_audit_event;
                            UPDATE public.admin_lifecycle_state
                            SET admin_count = 0,
                                bootstrap_consumed = true,
                                bootstrap_consumed_at = statement_timestamp(),
                                recovery_required = true,
                                last_recovery_at = NULL,
                                updated_at = statement_timestamp()
                            WHERE singleton_id = 1;
                            """.getBytes(StandardCharsets.UTF_8))
            );
        }
    }

    private Long countAdmins() {
        return migratorJdbc().queryForObject(
                "SELECT COUNT(*) FROM public.usuario WHERE perfil = 'ADMIN'",
                Long.class
        );
    }

    private JdbcTemplate migratorJdbc() {
        return new JdbcTemplate(migratorDataSource());
    }

    private DataSource migratorDataSource() {
        TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(settings.url());
        dataSource.setUsername(settings.migratorUsername());
        dataSource.setPassword(settings.migratorPassword());
        return dataSource;
    }

    private CliEnvironment offlineEnvironment() {
        TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
        return new CliEnvironment(
                settings.url(),
                settings.offlineUsername(),
                settings.offlinePassword(),
                "test-operator",
                "Test Operator"
        );
    }
}
