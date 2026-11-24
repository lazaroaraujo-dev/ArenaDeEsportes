package br.com.arena.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IsolatedPostgresIntegrationTest
class AdminLifecycleStateFreshInstallIntegrationTest {

    @Autowired
    private JdbcTemplate runtimeJdbc;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetToPendingState() throws SQLException {
        try (java.sql.Connection connection = migratorJdbc().getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new org.springframework.core.io.ByteArrayResource("""
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
                            """.getBytes(StandardCharsets.UTF_8))
            );
        }
    }

    @Test
    void freshInstallStartsInPendingState() {
        Map<String, Object> state = migratorJdbc().queryForMap(
                "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );

        assertEquals(1, state.get("singleton_id"));
        assertEquals(0L, ((Number) state.get("admin_count")).longValue());
        assertEquals(false, state.get("bootstrap_consumed"));
        assertEquals(false, state.get("recovery_required"));
        assertEquals(0L, countAdmins());
    }

    @Test
    void bootstrapCreatesFirstAdminAndConsumesMarker() {
        Long userId = offlineJdbc().queryForObject(
                """
                SELECT public.h04_bootstrap_first_admin(
                    'First Admin', 'first.admin@example.invalid', '000', 'hash',
                    'test-operator'
                )
                """,
                Long.class
        );

        assertNotNull(userId);
        assertEquals(1L, countAdmins());

        Map<String, Object> state = migratorJdbc().queryForMap(
                "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        assertEquals(1L, ((Number) state.get("admin_count")).longValue());
        assertEquals(true, state.get("bootstrap_consumed"));
        assertNotNull(state.get("bootstrap_consumed_at"));
        assertEquals(false, state.get("recovery_required"));

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
    }

    @Test
    void secondBootstrapFailsAfterMarkerConsumed() {
        bootstrapCreatesFirstAdminAndConsumesMarker();

        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> offlineJdbc().queryForObject(
                        """
                        SELECT public.h04_bootstrap_first_admin(
                            'Second', 'second@example.invalid', '000', 'hash',
                            'test-operator'
                        )
                        """,
                        Long.class
                )
        );

        assertTrue(error.getCause() instanceof org.postgresql.util.PSQLException);
        assertTrue(error.getMessage().contains("bootstrap already consumed"));
        assertEquals(1L, countAdmins());
    }

    @Test
    void legacyBootstrapSignatureIsDeniedForOfflineOperator() {
        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> offlineJdbc().queryForObject(
                        """
                        SELECT public.h04_bootstrap_first_admin(
                            'Legacy', 'legacy@example.invalid', '000', 'hash'
                        )
                        """,
                        Long.class
                )
        );

        assertTrue(error.getCause() instanceof org.postgresql.util.PSQLException);
        assertTrue(error.getCause().getMessage().contains("permission denied for function"),
                "A assinatura legada de bootstrap não pode ser executada por arena_offline_operator");
    }

    private Long countAdmins() {
        return runtimeJdbc.queryForObject(
                "SELECT COUNT(*) FROM public.usuario WHERE perfil = 'ADMIN'",
                Long.class
        );
    }

    private JdbcTemplate migratorJdbc() {
        DataSource migratorDataSource = flyway.getConfiguration().getDataSource();
        return new JdbcTemplate(migratorDataSource);
    }

    private JdbcTemplate offlineJdbc() {
        return new JdbcTemplate(roleDataSource("TEST_POSTGRES_OFFLINE_USERNAME", "TEST_POSTGRES_OFFLINE_PASSWORD"));
    }

    private DataSource roleDataSource(String usernameVariable, String passwordVariable) {
        TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
        String username = System.getenv(usernameVariable);
        String password = System.getenv(passwordVariable);
        if (username == null || password == null) {
            throw new IllegalStateException(
                    "Missing " + usernameVariable + " or " + passwordVariable
            );
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(settings.url());
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
