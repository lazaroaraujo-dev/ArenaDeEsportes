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

    private static final String SIGNATURE_REGISTRAR_CLIENTE =
            "public.h04_registrar_cliente(character varying, character varying, character varying, character varying)";
    private static final String SIGNATURE_GET_STATE =
            "public.h04_get_admin_lifecycle_state()";
    private static final String SIGNATURE_APPEND_AUDIT =
            "public.h04_append_audit_event(character varying, character varying, character varying, "
                    + "character varying, bigint, character varying, bigint, character varying, "
                    + "character varying, character varying, text, character varying, character varying, "
                    + "character varying)";
    private static final String SIGNATURE_DELETE =
            "public.h04_admin_excluir_usuario(bigint, bigint, character varying, text, "
                    + "character varying, character varying)";
    private static final String SIGNATURE_PROFILE =
            "public.h04_admin_alterar_perfil(bigint, bigint, character varying, "
                    + "character varying, text, character varying, character varying)";
    private static final String SIGNATURE_BOOTSTRAP_NEW =
            "public.h04_bootstrap_first_admin(character varying, character varying, "
                    + "character varying, character varying, character varying)";
    private static final String SIGNATURE_RECOVERY_NEW =
            "public.h04_recover_admin(bigint, character varying, character varying, "
                    + "character varying, character varying, character varying, text, character varying)";
    private static final String SIGNATURE_RECONCILE =
            "public.h04_reconcile_admin_state(text)";

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
    void offlineOperatorCanExecuteOnlyNewAdminFunctionSignatures() {
        JdbcTemplate offlineJdbc = offlineJdbc();

        assertFalse(
                hasFunctionPrivilege(
                        offlineJdbc,
                        "arena_offline_operator",
                        "public.h04_bootstrap_first_admin(character varying, character varying, character varying, character varying)"
                ),
                "Assinatura legada de bootstrap não pode ter EXECUTE"
        );
        assertFalse(
                hasFunctionPrivilege(
                        offlineJdbc,
                        "arena_offline_operator",
                        "public.h04_recover_admin(bigint, character varying, character varying, character varying, character varying)"
                ),
                "Assinatura legada de recovery não pode ter EXECUTE"
        );
        assertTrue(
                hasFunctionPrivilege(
                        offlineJdbc,
                        "arena_offline_operator",
                        "public.h04_bootstrap_first_admin(character varying, character varying, character varying, character varying, character varying)"
                ),
                "Assinatura nova de bootstrap deve ter EXECUTE"
        );
        assertTrue(
                hasFunctionPrivilege(
                        offlineJdbc,
                        "arena_offline_operator",
                        "public.h04_recover_admin(bigint, character varying, character varying, character varying, character varying, character varying, text, character varying)"
                ),
                "Assinatura nova de recovery deve ter EXECUTE"
        );
        assertTrue(
                hasFunctionPrivilege(offlineJdbc, "arena_offline_operator", SIGNATURE_RECONCILE),
                "reconcile é a única outra função permitida ao operador offline"
        );

        // V4.5: nenhuma função auxiliar é executada diretamente pelo operador offline.
        for (String signature : new String[]{
                SIGNATURE_REGISTRAR_CLIENTE, SIGNATURE_DELETE, SIGNATURE_PROFILE,
                SIGNATURE_GET_STATE, SIGNATURE_APPEND_AUDIT}) {
            assertFalse(
                    hasFunctionPrivilege(offlineJdbc, "arena_offline_operator", signature),
                    "offline não deve ter EXECUTE em " + signature
            );
        }
        assertEquals("arena_offline_operator",
                offlineJdbc.queryForObject("SELECT current_user", String.class));
        assertThrows(
                DataAccessException.class,
                () -> offlineJdbc.queryForObject(
                        "SELECT status FROM public.h04_admin_excluir_usuario(1, 2, NULL, NULL, NULL, NULL)",
                        String.class
                ),
                "offline não pode executar exclusão administrativa"
        );
        assertThrows(
                DataAccessException.class,
                () -> offlineJdbc.queryForObject(
                        """
                        SELECT public.h04_append_audit_event(
                            'BOOTSTRAP', 'SUCCEEDED', 'BOOTSTRAP_CLI', 'OPERATOR',
                            NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
                        )
                        """,
                        Long.class
                ),
                "offline não pode gravar auditoria diretamente"
        );
    }

    @Test
    void onlyAdminWriterCanExecuteTheV42DeletionFunction() {
        String functionSignature =
                "public.h04_admin_excluir_usuario(bigint, bigint, character varying, text, character varying, character varying)";

        assertTrue(
                hasFunctionPrivilege(writerJdbc(), "arena_admin_writer", functionSignature),
                "writer must be able to execute the self-contained deletion function"
        );
        assertFalse(
                hasFunctionPrivilege(runtimeJdbc, "arena_runtime", functionSignature),
                "runtime must not be able to execute the administrative deletion function"
        );
        assertFalse(
                hasFunctionPrivilege(runtimeJdbc, "arena_offline_operator", functionSignature),
                "offline operator must not be able to execute the administrative deletion function"
        );
        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.queryForObject(
                        "SELECT status FROM public.h04_admin_excluir_usuario(1, 2, NULL, NULL, NULL, NULL)",
                        String.class
                ),
                "runtime must fail when attempting to call the deletion function directly"
        );
    }

    @Test
    void onlyAdminWriterCanExecuteTheV43ProfileFunction() {
        JdbcTemplate writerJdbc = writerJdbc();

        assertTrue(
                hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_PROFILE),
                "writer deve executar a função de alteração de perfil"
        );
        assertFalse(
                hasFunctionPrivilege(runtimeJdbc, "arena_runtime", SIGNATURE_PROFILE),
                "runtime não deve executar a função de alteração de perfil"
        );
        assertFalse(
                hasFunctionPrivilege(offlineJdbc(), "arena_offline_operator", SIGNATURE_PROFILE),
                "offline não deve executar a função de alteração de perfil"
        );
        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.queryForObject(
                        "SELECT status FROM public.h04_admin_alterar_perfil(1, 2, 'CLIENTE', NULL, NULL, NULL, NULL)",
                        String.class
                ),
                "runtime deve falhar ao chamar diretamente a função de perfil"
        );
    }

    @Test
    void runtimeUpdateIsRestrictedToPersonalDataColumns() {
        String email = "v44_update_columns@example.invalid";
        Long id = migratorJdbc().queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('V4.4 update probe', ?, '000', 'hash-inicial', 'CLIENTE')
                RETURNING id
                """,
                Long.class,
                email
        );

        try {
            assertTrue(hasColumnPrivilege("usuario", "nome", "UPDATE"));
            assertTrue(hasColumnPrivilege("usuario", "email", "UPDATE"));
            assertTrue(hasColumnPrivilege("usuario", "telefone", "UPDATE"));
            assertFalse(hasColumnPrivilege("usuario", "perfil", "UPDATE"));
            assertFalse(hasColumnPrivilege("usuario", "senha", "UPDATE"));
            assertFalse(hasColumnPrivilege("usuario", "id", "UPDATE"));

            assertEquals(1, runtimeJdbc.update(
                    "UPDATE public.usuario SET nome = ?, email = ?, telefone = ? WHERE id = ?",
                    "Nome atualizado", "v44_atualizado@example.invalid", "11999998888", id
            ));

            assertThrows(
                    DataAccessException.class,
                    () -> runtimeJdbc.update(
                            "UPDATE public.usuario SET perfil = 'ADMIN' WHERE id = ?", id
                    ),
                    "runtime não pode atualizar perfil"
            );
            assertThrows(
                    DataAccessException.class,
                    () -> runtimeJdbc.update(
                            "UPDATE public.usuario SET senha = 'nova-senha' WHERE id = ?", id
                    ),
                    "runtime não pode atualizar senha"
            );

            assertEquals("CLIENTE", migratorJdbc().queryForObject(
                    "SELECT perfil FROM public.usuario WHERE id = ?", String.class, id
            ));
            assertEquals("hash-inicial", migratorJdbc().queryForObject(
                    "SELECT senha FROM public.usuario WHERE id = ?", String.class, id
            ));
        } finally {
            migratorJdbc().update("DELETE FROM public.usuario WHERE id = ?", id);
        }
    }

    @Test
    void runtimeCannotDeleteUsuarioDirectlyOrExecuteAdministrativeFunctions() {
        String email = "v42_runtime_delete_denied@example.invalid";
        Long id = migratorJdbc().queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Runtime delete probe', ?, '000', 'hash', 'CLIENTE')
                RETURNING id
                """,
                Long.class,
                email
        );

        try {
            assertThrows(
                    DataAccessException.class,
                    () -> runtimeJdbc.update("DELETE FROM public.usuario WHERE id = ?", id),
                    "runtime must not be able to delete users by direct SQL"
            );
            assertEquals(
                    1L,
                    migratorJdbc().queryForObject(
                            "SELECT count(*) FROM public.usuario WHERE id = ?", Long.class, id
                    ),
                    "a negativa de DELETE não pode remover o usuário"
            );

            assertFalse(hasTablePrivilege("usuario", "INSERT"));
            assertFalse(hasTablePrivilege("usuario", "DELETE"));
            assertTrue(hasTablePrivilege("usuario", "SELECT"));
            assertFalse(hasTablePrivilege("usuario", "UPDATE"),
                    "V4.4: não pode haver UPDATE geral em usuario");

            assertTrue(
                    hasFunctionPrivilege(runtimeJdbc, "arena_runtime", SIGNATURE_REGISTRAR_CLIENTE),
                    "cadastro público continua disponível ao runtime"
            );
            // V4.5: nenhuma função administrativa ou auxiliar é executada
            // diretamente pelo runtime.
            for (String signature : new String[]{
                    SIGNATURE_DELETE, SIGNATURE_PROFILE, SIGNATURE_GET_STATE, SIGNATURE_APPEND_AUDIT,
                    SIGNATURE_BOOTSTRAP_NEW, SIGNATURE_RECOVERY_NEW, SIGNATURE_RECONCILE}) {
                assertFalse(
                        hasFunctionPrivilege(runtimeJdbc, "arena_runtime", signature),
                        "runtime não deve ter EXECUTE em " + signature
                );
            }

            assertThrows(
                    DataAccessException.class,
                    () -> runtimeJdbc.queryForObject(
                            "SELECT p_admin_count FROM public.h04_get_admin_lifecycle_state()",
                            Long.class
                    ),
                    "runtime não pode ler o estado administrativo diretamente"
            );
            assertThrows(
                    DataAccessException.class,
                    () -> runtimeJdbc.queryForObject(
                            """
                            SELECT public.h04_append_audit_event(
                                'PROMOTE', 'SUCCEEDED', 'HTTP', 'USER',
                                NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL
                            )
                            """,
                            Long.class
                    ),
                    "runtime não pode gravar auditoria diretamente"
            );
        } finally {
            migratorJdbc().update("DELETE FROM public.usuario WHERE id = ?", id);
        }
    }

    @Test
    void writerCannotExecuteRevokedAuxiliaryFunctionsNorHoldRoleMemberships() {
        JdbcTemplate writerJdbc = writerJdbc();

        assertEquals("arena_admin_writer", writerJdbc.queryForObject("SELECT current_user", String.class));

        assertFalse(hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_GET_STATE));
        assertFalse(hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_APPEND_AUDIT));
        assertFalse(hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_REGISTRAR_CLIENTE));
        assertFalse(hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_BOOTSTRAP_NEW));
        assertFalse(hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_RECOVERY_NEW));
        assertFalse(hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_RECONCILE));
        assertTrue(hasFunctionPrivilege(writerJdbc, "arena_admin_writer", SIGNATURE_DELETE));

        assertEquals(
                0L,
                writerJdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_auth_members
                        WHERE member = 'arena_admin_writer'::regrole
                        """,
                        Long.class
                ),
                "writer não pode pertencer a nenhuma outra role"
        );
        assertFalse(Boolean.TRUE.equals(writerJdbc.queryForObject(
                "SELECT pg_has_role('arena_admin_writer', 'arena_migrator', 'MEMBER')",
                Boolean.class
        )));
        assertThrows(
                DataAccessException.class,
                () -> writerJdbc.queryForObject(
                        "SELECT p_admin_count FROM public.h04_get_admin_lifecycle_state()",
                        Long.class
                ),
                "função auxiliar revogada pela V4.2 deve falhar na conexão do writer"
        );
    }

    @Test
    void migratorOwnsAdministrativeObjectsAndRemainsTheOnlyFlywayRole() {
        JdbcTemplate migratorJdbc = migratorJdbc();

        assertEquals(
                6L,
                migratorJdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          AND c.relkind = 'r'
                          AND c.relowner = 'arena_migrator'::regrole
                          AND c.relname IN ('usuario', 'quadra', 'agendamento',
                              'admin_lifecycle_state', 'admin_audit_event', 'flyway_schema_history')
                        """,
                        Long.class
                )
        );
        assertEquals(
                0L,
                migratorJdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public'
                          AND c.relkind = 'S'
                          AND c.relowner <> 'arena_migrator'::regrole
                        """,
                        Long.class
                ),
                "todas as sequences públicas devem pertencer ao migrator"
        );
        assertEquals(
                0L,
                migratorJdbc.queryForObject(
                        """
                        SELECT count(*) FROM pg_proc p
                        JOIN pg_namespace n ON n.oid = p.pronamespace
                        WHERE n.nspname = 'public'
                          AND p.proname LIKE 'h04_%'
                          AND p.proowner <> 'arena_migrator'::regrole
                        """,
                        Long.class
                ),
                "todas as funções h04_ devem pertencer ao migrator"
        );
        assertTrue(
                migratorJdbc.queryForObject(
                        """
                        SELECT count(*) > 0 FROM pg_trigger t
                        WHERE t.tgrelid = 'public.usuario'::regclass
                          AND NOT t.tgisinternal
                          AND t.tgenabled <> 'D'
                        """,
                        Boolean.class
                ),
                "os triggers administrativos de usuario devem estar ativos"
        );
        assertFalse(hasDatabasePrivilege(migratorJdbc, "arena_migrator", "CREATE"),
                "o provisionamento revoga CREATE do banco após instalar extensões");
        assertFalse(hasDatabasePrivilege("arena_runtime", "CREATE"));
        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.queryForObject(
                        "SELECT installed_rank FROM public.flyway_schema_history LIMIT 1",
                        Integer.class
                )
        );
    }

    private static boolean hasFunctionPrivilege(
            JdbcTemplate jdbc,
            String role,
            String functionSignature
    ) {
        Boolean granted = jdbc.queryForObject(
                "SELECT has_function_privilege(?, ?, 'EXECUTE')",
                Boolean.class,
                role,
                functionSignature
        );
        return Boolean.TRUE.equals(granted);
    }

    private boolean hasColumnPrivilege(String table, String column, String privilege) {
        Boolean granted = runtimeJdbc.queryForObject(
                "SELECT has_column_privilege(?, ?, ?, ?)",
                Boolean.class,
                "arena_runtime",
                "public." + table,
                column,
                privilege
        );
        return Boolean.TRUE.equals(granted);
    }

    @Test
    void runtimeGetsOnlyApplicationDmlAndSequenceUseNotFlywayMetadata() {
        for (String table : new String[]{"usuario", "quadra", "agendamento"}) {
            assertTrue(hasTablePrivilege(table, "SELECT"), table);

            if (!table.equals("usuario")) {
                assertTrue(hasTablePrivilege(table, "INSERT"), table);
            }

            if (table.equals("usuario")) {
                // V4.4: UPDATE é concedido por coluna, nunca em nível de tabela.
                assertFalse(hasTablePrivilege("usuario", "UPDATE"));
            } else {
                assertTrue(hasTablePrivilege(table, "UPDATE"), table);
            }
            // V4.2: exclusão de usuario é feita apenas pela função SECURITY DEFINER
            // executada por arena_admin_writer; o runtime não tem DELETE direto.
            assertEquals(!table.equals("usuario"), hasTablePrivilege(table, "DELETE"), table);
            assertFalse(hasTablePrivilege(table, "TRUNCATE"), table);
            assertFalse(hasTablePrivilege(table, "REFERENCES"), table);
            assertFalse(hasTablePrivilege(table, "TRIGGER"), table);
        }

        assertFalse(hasTablePrivilege("usuario", "INSERT"));
        assertFalse(hasTablePrivilege("usuario", "DELETE"));

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
        Long quadraId = runtimeJdbc.queryForObject(
                """
                INSERT INTO public.quadra (nome, tipo, ativa)
                VALUES ('H-02 privilege test', 'FUTVOLEI', true)
                RETURNING id
                """,
                Long.class
        );
        assertTrue(quadraId > 0);
        assertEquals(
                1,
                runtimeJdbc.update(
                        "UPDATE public.quadra SET nome = ? WHERE id = ?",
                        "H-02 privilege test updated",
                        quadraId
                )
        );
        assertEquals(
                1,
                runtimeJdbc.update("DELETE FROM public.quadra WHERE id = ?", quadraId)
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
    void runtimeCanRegisterClientUsingFunctionButCannotInsertDirectlyIntoUsuario() {
        String email = "h04_v41_function@example.invalid";

        Long id = runtimeJdbc.queryForObject(
                """
                SELECT public.h04_registrar_cliente(?, ?, ?, ?)
                """,
                Long.class,
                "H-04 V4.1 function test",
                email,
                "11999990000",
                "hash"
        );

        assertTrue(id > 0);
        assertEquals(
                "CLIENTE",
                runtimeJdbc.queryForObject(
                        "SELECT perfil FROM public.usuario WHERE id = ?",
                        String.class,
                        id
                )
        );

        assertThrows(
                DataAccessException.class,
                () -> runtimeJdbc.execute(
                        """
                        INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                        VALUES ('Direct insert forbidden', 'direct_insert@example.invalid', '000', 'hash', 'CLIENTE')
                        """
                )
        );
    }

    @Test
    void h04RegistrarClienteAlwaysCreatesCliente() {
        String email = "h04_v41_admin_attempt@example.invalid";

        Long id = runtimeJdbc.queryForObject(
                """
                SELECT public.h04_registrar_cliente(?, ?, ?, ?)
                """,
                Long.class,
                "H-04 V4.1 client-only",
                email,
                "11999990000",
                "hash"
        );

        assertTrue(id > 0);
        assertEquals(
                "CLIENTE",
                runtimeJdbc.queryForObject(
                        "SELECT perfil FROM public.usuario WHERE id = ?",
                        String.class,
                        id
                )
        );
    }

    @Test
    void futureTablesAndSequencesDoNotInheritRuntimeDml() {
        JdbcTemplate migratorJdbc = migratorJdbc();
        migratorJdbc.execute(
                """
                CREATE TABLE public.v46_future_probe (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
        );
        migratorJdbc.execute("CREATE SEQUENCE public.v46_future_seq");

        try {
            // V4.6: nenhum DML é herdado automaticamente pelo runtime.
            assertFalse(hasTablePrivilege("v46_future_probe", "SELECT"));
            assertFalse(hasTablePrivilege("v46_future_probe", "INSERT"));
            assertFalse(hasTablePrivilege("v46_future_probe", "UPDATE"));
            assertFalse(hasTablePrivilege("v46_future_probe", "DELETE"));
            assertFalse(hasSequencePrivilege("public.v46_future_seq", "USAGE"));
            assertFalse(hasSequencePrivilege("public.v46_future_seq", "SELECT"));

            assertThrows(
                    DataAccessException.class,
                    () -> runtimeJdbc.update(
                            "INSERT INTO public.v46_future_probe (value) VALUES ('herdado')"
                    ),
                    "runtime não pode inserir em tabela futura sem grant explícito"
            );

            // Grants explícitos continuam funcionando normalmente.
            migratorJdbc.execute(
                    "GRANT SELECT, INSERT ON TABLE public.v46_future_probe TO arena_runtime"
            );
            assertTrue(hasTablePrivilege("v46_future_probe", "SELECT"));
            assertTrue(hasTablePrivilege("v46_future_probe", "INSERT"));
            Long id = runtimeJdbc.queryForObject(
                    """
                    INSERT INTO public.v46_future_probe (value)
                    VALUES ('grant explicito')
                    RETURNING id
                    """,
                    Long.class
            );
            assertTrue(id > 0);
            assertEquals("grant explicito", runtimeJdbc.queryForObject(
                    "SELECT value FROM public.v46_future_probe WHERE id = ?",
                    String.class, id
            ));
        } finally {
            migratorJdbc.execute("DROP TABLE IF EXISTS public.v46_future_probe");
            migratorJdbc.execute("DROP SEQUENCE IF EXISTS public.v46_future_seq");
        }
    }

    @Test
    void defaultAclNaoConcedePrivilegioAoRuntime() {
        Long grants = migratorJdbc().queryForObject(
                """
                SELECT count(*)
                  FROM pg_default_acl d
                  CROSS JOIN LATERAL aclexplode(d.defaclacl) a
                 WHERE d.defaclnamespace = 'public'::regnamespace
                   AND d.defaclrole = 'arena_migrator'::regrole
                   AND a.grantee = 'arena_runtime'::regrole
                """,
                Long.class
        );
        assertEquals(0L, grants,
                "pg_default_acl não pode conceder privileges automáticos ao runtime");
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
