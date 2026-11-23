package br.com.arena.cli;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IsolatedPostgresIntegrationTest
class AdminCliRecoveryIntegrationTest {

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetToRecoveryRequiredState() throws SQLException {
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

    @Test
    void recoverySucceedsInRecoveryRequiredState() {
        StaticInputReader input = new StaticInputReader()
                .add("Perda do último ADMIN")
                .add("TICKET-12345")
                .add("") // novo usuário
                .add("Admin Recovered")
                .add("recovered@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("recovery");

        assertEquals(CliRunner.EXIT_SUCCESS, exitCode);

        Map<String, Object> state = migratorJdbc().queryForMap(
                "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        assertEquals(true, state.get("bootstrap_consumed"));
        assertEquals(false, state.get("recovery_required"));
        assertEquals(1L, ((Number) state.get("admin_count")).longValue());
        assertNotNull(state.get("last_recovery_at"));

        Long auditCount = migratorJdbc().queryForObject(
                "SELECT COUNT(*) FROM public.admin_audit_event WHERE operation = 'RECOVERY'",
                Long.class
        );
        assertEquals(1L, auditCount);

        Map<String, Object> auditEvent = migratorJdbc().queryForMap(
                "SELECT actor_ref, reason, change_ticket FROM public.admin_audit_event WHERE operation = 'RECOVERY'"
        );
        assertEquals("test-operator", auditEvent.get("actor_ref"));
        assertEquals("Perda do último ADMIN", auditEvent.get("reason"));
        assertEquals("TICKET-12345", auditEvent.get("change_ticket"));

        assertEquals(1L, countAdmins());
    }

    @Test
    void recoveryRejectsPendingState() throws SQLException {
        setPendingState();

        StaticInputReader input = new StaticInputReader()
                .add("Justificativa")
                .add("TICKET-99999")
                .add("")
                .add("Admin Recovered")
                .add("recovered@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("recovery");

        assertEquals(CliRunner.EXIT_BUSINESS_ERROR, exitCode);
        assertEquals(0L, countAdmins());
    }

    @Test
    void recoveryRejectsWhenAdminAlreadyExists() throws SQLException {
        // Estado CONSUMED com um ADMIN
        createConsumedState();

        StaticInputReader input = new StaticInputReader()
                .add("Tentativa inválida")
                .add("TICKET-00000")
                .add("")
                .add("Admin Recovered")
                .add("recovered@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("recovery");

        assertEquals(CliRunner.EXIT_BUSINESS_ERROR, exitCode);
        assertEquals(1L, countAdmins());
    }

    @Test
    void recoveryRejectsInconsistentState() throws SQLException {
        // admin_count=0 mas existe um ADMIN real (inconsistente)
        createInconsistentState();

        StaticInputReader input = new StaticInputReader()
                .add("Estado inconsistente")
                .add("TICKET-XXXXX")
                .add("")
                .add("Admin Recovered")
                .add("recovered@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("recovery");

        assertEquals(CliRunner.EXIT_BUSINESS_ERROR, exitCode);
    }

    @Test
    void recoveryRejectsMissingJustification() {
        StaticInputReader input = new StaticInputReader()
                .add("") // justificativa vazia
                .add("TICKET-12345")
                .add("")
                .add("Admin Recovered")
                .add("recovered@example.invalid")
                .add("0000000000")
                .addPassword("senhaSegura123!")
                .addPassword("senhaSegura123!");

        CliRunner runner = new CliRunner(offlineEnvironment(), input);
        int exitCode = runner.execute("recovery");

        assertEquals(CliRunner.EXIT_VALIDATION_ERROR, exitCode);
        assertEquals(0L, countAdmins());
    }

    private void setPendingState() throws SQLException {
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

    private void createConsumedState() throws SQLException {
        executeSetup("""
                TRUNCATE TABLE public.usuario CASCADE;
                TRUNCATE TABLE public.quadra CASCADE;
                TRUNCATE TABLE public.admin_audit_event;
                UPDATE public.admin_lifecycle_state
                SET admin_count = 1,
                    bootstrap_consumed = true,
                    bootstrap_consumed_at = statement_timestamp(),
                    recovery_required = false,
                    last_recovery_at = NULL,
                    updated_at = statement_timestamp()
                WHERE singleton_id = 1;
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Existing Admin', 'existing@example.invalid', '000', 'hash', 'ADMIN');
                """);
    }

    private void createInconsistentState() throws SQLException {
        executeSetup("""
                TRUNCATE TABLE public.usuario CASCADE;
                TRUNCATE TABLE public.quadra CASCADE;
                TRUNCATE TABLE public.admin_audit_event;
                UPDATE public.admin_lifecycle_state
                SET admin_count = 0,
                    bootstrap_consumed = true,
                    bootstrap_consumed_at = statement_timestamp(),
                    recovery_required = false,
                    last_recovery_at = NULL,
                    updated_at = statement_timestamp()
                WHERE singleton_id = 1;
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Orphan Admin', 'orphan@example.invalid', '000', 'hash', 'ADMIN');
                UPDATE public.admin_lifecycle_state
                SET admin_count = 0,
                    recovery_required = true,
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
