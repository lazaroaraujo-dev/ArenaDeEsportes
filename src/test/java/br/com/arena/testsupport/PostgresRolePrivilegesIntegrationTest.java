package br.com.arena.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IsolatedPostgresIntegrationTest
class PostgresRolePrivilegesIntegrationTest {

    @Autowired
    private JdbcTemplate runtimeJdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void runtimeAndMigratorAreSeparateLeastPrivilegeRoles() {
        assertEquals(
                "arena_runtime",
                runtimeJdbc.queryForObject("SELECT current_user", String.class)
        );
        assertRoleHasNoAdministrativeAttributes(runtimeJdbc, "arena_runtime");
        assertTrue(hasDatabasePrivilege("arena_runtime", "CONNECT"));
        assertFalse(hasDatabasePrivilege("arena_runtime", "CREATE"));
        assertFalse(hasDatabasePrivilege("arena_runtime", "TEMP"));
        assertTrue(hasSchemaPrivilege("arena_runtime", "USAGE"));
        assertFalse(hasSchemaPrivilege("arena_runtime", "CREATE"));

        JdbcTemplate migratorJdbc = migratorJdbc();
        assertEquals(
                "arena_migrator",
                migratorJdbc.queryForObject("SELECT current_user", String.class)
        );
        assertRoleHasNoAdministrativeAttributes(migratorJdbc, "arena_migrator");
        assertTrue(hasDatabasePrivilege(migratorJdbc, "arena_migrator", "CONNECT"));
        assertFalse(hasDatabasePrivilege(migratorJdbc, "arena_migrator", "CREATE"));
        assertFalse(hasDatabasePrivilege(migratorJdbc, "arena_migrator", "TEMP"));
        assertTrue(hasSchemaPrivilege(migratorJdbc, "arena_migrator", "USAGE"));
        assertTrue(hasSchemaPrivilege(migratorJdbc, "arena_migrator", "CREATE"));

        assertEquals(
                3L,
                migratorJdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_class
                        WHERE relnamespace = 'public'::regnamespace
                          AND relkind = 'r'
                          AND relname IN ('usuario', 'quadra', 'agendamento')
                          AND relowner = 'arena_migrator'::regrole
                        """,
                        Long.class
                )
        );
    }

    @Test
    void writerAndOfflineRolesExistWithLeastPrivilegeAndNoMemberships() {
        for (String role : new String[]{"arena_admin_writer", "arena_offline_operator"}) {
            assertRoleHasNoAdministrativeAttributes(runtimeJdbc, role);
            assertTrue(hasDatabasePrivilege(role, "CONNECT"), role);
            assertFalse(hasDatabasePrivilege(role, "CREATE"), role);
            assertFalse(hasDatabasePrivilege(role, "TEMP"), role);
            assertTrue(hasSchemaPrivilege(role, "USAGE"), role);
            assertFalse(hasSchemaPrivilege(role, "CREATE"), role);
        }

        JdbcTemplate writerJdbc = writerJdbc();
        assertEquals(
                "arena_admin_writer",
                writerJdbc.queryForObject("SELECT current_user", String.class)
        );
        JdbcTemplate offlineJdbc = offlineJdbc();
        assertEquals(
                "arena_offline_operator",
                offlineJdbc.queryForObject("SELECT current_user", String.class)
        );

        assertEquals(
                Long.valueOf(0),
                runtimeJdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_auth_members
                        WHERE member IN (
                            'arena_runtime'::regrole,
                            'arena_migrator'::regrole,
                            'arena_admin_writer'::regrole,
                            'arena_offline_operator'::regrole
                        )
                        """,
                        Long.class
                ),
                "No application or operator role may hold a membership"
        );
    }

    @Test
    void writerAndOfflineHaveNoDirectTableDmlOrDdl() {
        JdbcTemplate writerJdbc = writerJdbc();
        JdbcTemplate offlineJdbc = offlineJdbc();

        for (String table : new String[]{"usuario", "quadra", "agendamento", "flyway_schema_history"}) {
            String qualifiedTable = "public." + table;
            for (String privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
                assertFalse(
                        hasTablePrivilege(writerJdbc, "arena_admin_writer", qualifiedTable, privilege),
                        "writer should not have " + privilege + " on " + qualifiedTable
                );
                assertFalse(
                        hasTablePrivilege(offlineJdbc, "arena_offline_operator", qualifiedTable, privilege),
                        "offline should not have " + privilege + " on " + qualifiedTable
                );
            }
        }

        for (JdbcTemplate jdbc : new JdbcTemplate[]{writerJdbc, offlineJdbc}) {
            assertThrows(
                    DataAccessException.class,
                    () -> jdbc.execute(
                            "CREATE TABLE public.h04_writer_offline_ddl_forbidden (id integer)"
                    ),
                    "DDL must be denied"
            );
            assertThrows(
                    DataAccessException.class,
                    () -> jdbc.execute("CREATE TEMP TABLE h04_writer_offline_temp_forbidden (id integer)"),
                    "TEMP TABLE must be denied"
            );
            assertThrows(
                    DataAccessException.class,
                    () -> jdbc.execute("CREATE SCHEMA h04_writer_offline_schema_forbidden"),
                    "CREATE SCHEMA must be denied"
            );
        }
    }

    @Test
    void runtimeGetsOnlyApplicationDmlAndSequenceUseNotFlywayMetadata() {
        for (String table : new String[]{"usuario", "quadra", "agendamento"}) {
            assertTrue(hasTablePrivilege(table, "SELECT"), table);
            assertTrue(hasTablePrivilege(table, "INSERT"), table);
            assertTrue(hasTablePrivilege(table, "UPDATE"), table);
            assertTrue(hasTablePrivilege(table, "DELETE"), table);
            assertFalse(hasTablePrivilege(table, "TRUNCATE"), table);
            assertFalse(hasTablePrivilege(table, "REFERENCES"), table);
            assertFalse(hasTablePrivilege(table, "TRIGGER"), table);
        }

        assertFalse(hasTablePrivilege("flyway_schema_history", "SELECT"));
        assertFalse(hasTablePrivilege("flyway_schema_history", "INSERT"));
        assertFalse(hasTablePrivilege("flyway_schema_history", "UPDATE"));
        assertFalse(hasTablePrivilege("flyway_schema_history", "DELETE"));

        for (String table : new String[]{"usuario", "quadra", "agendamento"}) {
            String sequence = runtimeJdbc.queryForObject(
                    "SELECT pg_get_serial_sequence('public." + table + "', 'id')",
                    String.class
            );
            assertTrue(hasSequencePrivilege(sequence, "USAGE"), table);
            assertTrue(hasSequencePrivilege(sequence, "SELECT"), table);
            assertFalse(hasSequencePrivilege(sequence, "UPDATE"), table);
        }
    }

    @Test
    void runtimeCanUseApplicationDmlButCannotExecuteDdlOrReadFlywayHistory() {
        Long usuarioId = runtimeJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('H-02 privilege test', 'h02@example.invalid', '000', 'hash', 'CLIENTE')
                RETURNING id
                """,
                Long.class
        );
        assertTrue(usuarioId > 0);
        assertEquals(
                1,
                runtimeJdbc.update(
                        "UPDATE public.usuario SET nome = ? WHERE id = ?",
                        "H-02 privilege test updated",
                        usuarioId
                )
        );
        assertEquals(
                1,
                runtimeJdbc.update("DELETE FROM public.usuario WHERE id = ?", usuarioId)
        );

        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.queryForObject(
                        "SELECT installed_rank FROM public.flyway_schema_history LIMIT 1",
                        Integer.class
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.execute(
                        "CREATE TABLE public.h02_runtime_ddl_forbidden (id integer)"
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.execute("CREATE TEMP TABLE h02_runtime_temp_forbidden (id integer)")
        );
        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.execute("CREATE SCHEMA h02_runtime_schema_forbidden")
        );
    }

    @Test
    void migratorDefaultPrivilegesCoverFutureTablesAndSequences() {
        JdbcTemplate migratorJdbc = migratorJdbc();
        migratorJdbc.execute(
                """
                CREATE TABLE public.h02_runtime_privilege_probe (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
        );

        try {
            assertTrue(hasTablePrivilege("h02_runtime_privilege_probe", "SELECT"));
            assertTrue(hasTablePrivilege("h02_runtime_privilege_probe", "INSERT"));
            assertTrue(hasTablePrivilege("h02_runtime_privilege_probe", "UPDATE"));
            assertTrue(hasTablePrivilege("h02_runtime_privilege_probe", "DELETE"));

            Long id = runtimeJdbc.queryForObject(
                    """
                    INSERT INTO public.h02_runtime_privilege_probe (value)
                    VALUES ('future default privilege')
                    RETURNING id
                    """,
                    Long.class
            );
            assertTrue(id > 0);
            assertEquals(
                    "future default privilege",
                    runtimeJdbc.queryForObject(
                            "SELECT value FROM public.h02_runtime_privilege_probe WHERE id = ?",
                            String.class,
                            id
                    )
            );
        } finally {
            migratorJdbc.execute(
                    "DROP TABLE IF EXISTS public.h02_runtime_privilege_probe"
            );
        }
    }

    private static void assertRoleHasNoAdministrativeAttributes(
            JdbcTemplate jdbc,
            String role) {
        Boolean leastPrivilege = jdbc.queryForObject(
                """
                SELECT rolcanlogin
                   AND NOT rolsuper
                   AND NOT rolcreatedb
                   AND NOT rolcreaterole
                   AND NOT rolreplication
                   AND NOT rolbypassrls
                   AND NOT rolinherit
                FROM pg_roles
                WHERE rolname = ?
                """,
                Boolean.class,
                role
        );
        assertEquals(Boolean.TRUE, leastPrivilege, role);
    }

    private boolean hasDatabasePrivilege(String role, String privilege) {
        return hasDatabasePrivilege(runtimeJdbc, role, privilege);
    }

    private static boolean hasDatabasePrivilege(
            JdbcTemplate jdbc,
            String role,
            String privilege) {
        Boolean result = jdbc.queryForObject(
                "SELECT has_database_privilege(?, current_database(), ?)",
                Boolean.class,
                role,
                privilege
        );
        return Boolean.TRUE.equals(result);
    }

    private boolean hasSchemaPrivilege(String role, String privilege) {
        return hasSchemaPrivilege(runtimeJdbc, role, privilege);
    }

    private static boolean hasSchemaPrivilege(
            JdbcTemplate jdbc,
            String role,
            String privilege) {
        Boolean result = jdbc.queryForObject(
                "SELECT has_schema_privilege(?, 'public', ?)",
                Boolean.class,
                role,
                privilege
        );
        return Boolean.TRUE.equals(result);
    }

    private boolean hasTablePrivilege(String table, String privilege) {
        return hasTablePrivilege(runtimeJdbc, "arena_runtime", "public." + table, privilege);
    }

    private static boolean hasTablePrivilege(
            JdbcTemplate jdbc,
            String role,
            String table,
            String privilege) {
        Boolean result = jdbc.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)",
                Boolean.class,
                role,
                table,
                privilege
        );
        return Boolean.TRUE.equals(result);
    }

    private boolean hasSequencePrivilege(String sequence, String privilege) {
        Boolean result = runtimeJdbc.queryForObject(
                "SELECT has_sequence_privilege(?, ?, ?)",
                Boolean.class,
                "arena_runtime",
                sequence,
                privilege
        );
        return Boolean.TRUE.equals(result);
    }

    private JdbcTemplate migratorJdbc() {
        DataSource migratorDataSource = flyway.getConfiguration().getDataSource();
        return new JdbcTemplate(migratorDataSource);
    }

    private JdbcTemplate writerJdbc() {
        return new JdbcTemplate(roleDataSource("TEST_POSTGRES_WRITER_USERNAME", "TEST_POSTGRES_WRITER_PASSWORD"));
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
