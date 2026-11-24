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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IsolatedPostgresIntegrationTest
class AdminLifecycleStateIntegrationTest {

    @Autowired
    private JdbcTemplate runtimeJdbc;

    @Autowired
    private Flyway flyway;

    @BeforeEach
    void resetToLegacyRecoveryRequiredState() throws SQLException {
        try (java.sql.Connection connection = migratorJdbc().getDataSource().getConnection()) {
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
    void legacyEmptyDatabaseStartsInRecoveryRequiredState() {
        Map<String, Object> state = migratorJdbc().queryForMap(
                "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );

        assertEquals(1, state.get("singleton_id"));
        assertEquals(0L, ((Number) state.get("admin_count")).longValue());
        assertEquals(true, state.get("bootstrap_consumed"));
        assertEquals(true, state.get("recovery_required"));
        assertNotNull(state.get("bootstrap_consumed_at"));
        assertEquals(0L, countAdmins());
    }

    @Test
    void bootstrapIsRejectedInRecoveryRequiredState() {
        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> offlineJdbc().queryForObject(
                        """
                        SELECT public.h04_bootstrap_first_admin(
                            'Bootstrap', 'bootstrap@example.invalid', '000', 'hash',
                            'test-operator'
                        )
                        """,
                        Long.class
                )
        );

        assertTrue(error.getCause() instanceof org.postgresql.util.PSQLException);
        assertTrue(error.getMessage().contains("recovery is required"));
        assertEquals(0L, countAdmins());
    }

    @Test
    void legacyRecoverySignatureIsDeniedForOfflineOperator() {
        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> offlineJdbc().queryForObject(
                        """
                        SELECT public.h04_recover_admin(
                            NULL, 'Legacy', 'legacy@example.invalid', '000', 'hash'
                        )
                        """,
                        Long.class
                )
        );

        assertTrue(error.getCause() instanceof org.postgresql.util.PSQLException);
        assertTrue(error.getCause().getMessage().contains("permission denied for function"),
                "A assinatura legada de recovery não pode ser executada por arena_offline_operator");
    }

    @Test
    void recoveryCreatesAdminAndClearsRecoveryFlag() {
        Long userId = offlineJdbc().queryForObject(
                """
                SELECT public.h04_recover_admin(
                    NULL, 'Recovered', 'recovered@example.invalid', '000', 'hash',
                    'test-operator', 'perda do último ADMIN', 'TICKET-12345'
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
        assertEquals(false, state.get("recovery_required"));
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
        assertEquals("perda do último ADMIN", auditEvent.get("reason"));
        assertEquals("TICKET-12345", auditEvent.get("change_ticket"));
    }

    @Test
    void insertClienteDoesNotChangeAdminCount() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        Long stateCountBefore = adminCount();

        runtimeJdbc.update(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Cliente A', 'cliente.a@example.invalid', '000', 'hash', 'CLIENTE')
                """
        );

        assertEquals(stateCountBefore, adminCount());
    }

    @Test
    void insertAdminIncrementsAdminCount() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        Long stateCountBefore = adminCount();

        runtimeJdbc.update(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Admin Two', 'admin.two@example.invalid', '000', 'hash', 'ADMIN')
                """
        );

        assertEquals(stateCountBefore + 1, adminCount());
        assertEquals(stateCountBefore + 1, countAdmins());
    }

    @Test
    void promoteClienteToAdminIncrementsAdminCount() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        Long clienteId = runtimeJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Cliente B', 'cliente.b@example.invalid', '000', 'hash', 'CLIENTE')
                RETURNING id
                """,
                Long.class
        );

        Long stateCountBefore = adminCount();

        runtimeJdbc.update(
                "UPDATE public.usuario SET perfil = 'ADMIN' WHERE id = ?",
                clienteId
        );

        assertEquals(stateCountBefore + 1, adminCount());
    }

    @Test
    void demoteAdminToClienteDecrementsCountWhenMoreThanOneAdmin() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        runtimeJdbc.update(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Admin Two', 'admin.two@example.invalid', '000', 'hash', 'ADMIN')
                """
        );

        Long adminTwoId = runtimeJdbc.queryForObject(
                "SELECT id FROM public.usuario WHERE email = 'admin.two@example.invalid'",
                Long.class
        );

        Long stateCountBefore = adminCount();

        runtimeJdbc.update(
                "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE id = ?",
                adminTwoId
        );

        assertEquals(stateCountBefore - 1, adminCount());
    }

    @Test
    void deleteAdminDecrementsCountWhenMoreThanOneAdmin() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        runtimeJdbc.update(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Admin Two', 'admin.two@example.invalid', '000', 'hash', 'ADMIN')
                """
        );

        Long adminTwoId = runtimeJdbc.queryForObject(
                "SELECT id FROM public.usuario WHERE email = 'admin.two@example.invalid'",
                Long.class
        );

        Long stateCountBefore = adminCount();

        runtimeJdbc.update(
                "DELETE FROM public.usuario WHERE id = ?",
                adminTwoId
        );

        assertEquals(stateCountBefore - 1, adminCount());
    }

    @Test
    void cannotDemoteLastAdmin() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        Long onlyAdminId = runtimeJdbc.queryForObject(
                "SELECT id FROM public.usuario WHERE perfil = 'ADMIN' LIMIT 1",
                Long.class
        );

        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.update(
                        "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE id = ?",
                        onlyAdminId
                )
        );

        assertTrue(error.getCause() instanceof org.postgresql.util.PSQLException);
        assertTrue(error.getMessage().contains("last admin protection"));
        assertEquals(1L, countAdmins());
        assertEquals(1L, adminCount());
    }

    @Test
    void cannotDeleteLastAdmin() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        Long onlyAdminId = runtimeJdbc.queryForObject(
                "SELECT id FROM public.usuario WHERE perfil = 'ADMIN' LIMIT 1",
                Long.class
        );

        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.update(
                        "DELETE FROM public.usuario WHERE id = ?",
                        onlyAdminId
                )
        );

        assertTrue(error.getCause() instanceof org.postgresql.util.PSQLException);
        assertTrue(error.getMessage().contains("last admin protection"));
        assertEquals(1L, countAdmins());
        assertEquals(1L, adminCount());
    }

    @Test
    void multiRowDemoteThatWouldLeaveZeroAdminsRollsBackEntireStatement() {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        runtimeJdbc.update(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Admin Two', 'admin.two@example.invalid', '000', 'hash', 'ADMIN')
                """
        );

        assertEquals(2L, countAdmins());

        DataAccessException error = assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.update(
                        "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE perfil = 'ADMIN'"
                )
        );

        assertTrue(error.getCause() instanceof org.postgresql.util.PSQLException);
        assertTrue(error.getMessage().contains("last admin protection"));
        assertEquals(2L, countAdmins());
        assertEquals(2L, adminCount());
    }

    @Test
    void concurrentDemotionsCannotReduceAdminCountBelowOne() throws Exception {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        runtimeJdbc.update(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Admin Two', 'admin.two@example.invalid', '000', 'hash', 'ADMIN')
                """
        );

        List<Long> adminIds = runtimeJdbc.queryForList(
                "SELECT id FROM public.usuario WHERE perfil = 'ADMIN' ORDER BY id",
                Long.class
        );
        assertEquals(2, adminIds.size());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        Future<?> future1 = executor.submit(() -> {
            latch.await();
            try {
                runtimeJdbc.update(
                        "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE id = ?",
                        adminIds.get(0)
                );
                return true;
            } catch (DataAccessException ex) {
                return false;
            }
        });

        Future<?> future2 = executor.submit(() -> {
            latch.await();
            try {
                runtimeJdbc.update(
                        "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE id = ?",
                        adminIds.get(1)
                );
                return true;
            } catch (DataAccessException ex) {
                return false;
            }
        });

        latch.countDown();

        boolean firstSucceeded = (Boolean) future1.get(10, TimeUnit.SECONDS);
        boolean secondSucceeded = (Boolean) future2.get(10, TimeUnit.SECONDS);

        executor.shutdown();

        assertTrue(
                firstSucceeded || secondSucceeded,
                "At least one demotion should succeed"
        );
        assertFalse(
                firstSucceeded && secondSucceeded,
                "Both demotions cannot succeed; one must protect the last admin"
        );
        assertEquals(1L, countAdmins());
        assertEquals(1L, adminCount());
    }

    @Test
    void reconcileTransitionsFromConsumedToRecoveryRequiredWhenNoAdminExists() throws SQLException {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        try (java.sql.Connection connection = migratorJdbc().getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new org.springframework.core.io.ByteArrayResource("""
                            TRUNCATE TABLE public.usuario CASCADE;
                            SELECT public.h04_reconcile_admin_state('lost admin simulation');
                            """.getBytes(StandardCharsets.UTF_8))
            );
        }

        Map<String, Object> state = migratorJdbc().queryForMap(
                "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        assertEquals(0L, ((Number) state.get("admin_count")).longValue());
        assertEquals(true, state.get("bootstrap_consumed"));
        assertEquals(true, state.get("recovery_required"));
        assertEquals(0L, countAdmins());
    }

    @Test
    void reconcileKeepsConsistentStateUnchanged() throws SQLException {
        recoveryCreatesAdminAndClearsRecoveryFlag();

        Long adminCountBefore = adminCount();
        Boolean recoveryRequiredBefore = migratorJdbc().queryForObject(
                "SELECT recovery_required FROM public.admin_lifecycle_state WHERE singleton_id = 1",
                Boolean.class
        );

        try (java.sql.Connection connection = migratorJdbc().getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new org.springframework.core.io.ByteArrayResource("""
                            SELECT public.h04_reconcile_admin_state('already consistent');
                            """.getBytes(StandardCharsets.UTF_8))
            );
        }

        Map<String, Object> state = migratorJdbc().queryForMap(
                "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        assertEquals(adminCountBefore, ((Number) state.get("admin_count")).longValue());
        assertEquals(recoveryRequiredBefore, state.get("recovery_required"));
    }

    @Test
    void runtimeCannotReadOrModifyLifecycleStateOrAuditTables() {
        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.queryForMap(
                        "SELECT * FROM public.admin_lifecycle_state WHERE singleton_id = 1"
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.update(
                        "UPDATE public.admin_lifecycle_state SET admin_count = 99 WHERE singleton_id = 1"
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.queryForMap(
                        "SELECT * FROM public.admin_audit_event LIMIT 1"
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.update(
                        "DELETE FROM public.admin_audit_event"
                )
        );
    }

    private Long countAdmins() {
        return runtimeJdbc.queryForObject(
                "SELECT COUNT(*) FROM public.usuario WHERE perfil = 'ADMIN'",
                Long.class
        );
    }

    private Long adminCount() {
        return migratorJdbc().queryForObject(
                "SELECT admin_count FROM public.admin_lifecycle_state WHERE singleton_id = 1",
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
