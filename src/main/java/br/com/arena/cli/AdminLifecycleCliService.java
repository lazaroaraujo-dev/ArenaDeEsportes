package br.com.arena.cli;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Serviço CLI que invoca as funções SECURITY DEFINER da migration V3 usando a
 * role {@code arena_offline_operator}. Não executa DML direto em
 * {@code usuario}, {@code admin_lifecycle_state} nem {@code admin_audit_event}.
 */
public class AdminLifecycleCliService {

    private final JdbcTemplate jdbcTemplate;

    public AdminLifecycleCliService(CliEnvironment environment) {
        this.jdbcTemplate = new JdbcTemplate(dataSource(environment));
    }

    public Long bootstrapFirstAdmin(
            String nome,
            String email,
            String telefone,
            String senhaHash,
            String actorRef) {
        return jdbcTemplate.queryForObject(
                """
                SELECT public.h04_bootstrap_first_admin(?, ?, ?, ?, ?)
                """,
                Long.class,
                nome, email, telefone, senhaHash, actorRef
        );
    }

    public Long recoverAdmin(
            Long userId,
            String nome,
            String email,
            String telefone,
            String senhaHash,
            String actorRef,
            String reason,
            String changeTicket) {
        return jdbcTemplate.queryForObject(
                """
                SELECT public.h04_recover_admin(?, ?, ?, ?, ?, ?, ?, ?)
                """,
                Long.class,
                userId, nome, email, telefone, senhaHash, actorRef, reason, changeTicket
        );
    }

    public boolean v3FunctionsExist() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_proc
                WHERE pronamespace = 'public'::regnamespace
                  AND (
                      (
                          proname = 'h04_bootstrap_first_admin'
                          AND pronargs = 5
                          AND array_to_string(proargtypes::regtype[], ',') =
                              'character varying,character varying,character varying,character varying,character varying'
                      )
                      OR
                      (
                          proname = 'h04_recover_admin'
                          AND pronargs = 8
                          AND array_to_string(proargtypes::regtype[], ',') =
                              'bigint,character varying,character varying,character varying,character varying,character varying,text,character varying'
                      )
                  )
                """,
                Integer.class
        );
        return count != null && count == 2;
    }

    private static DataSource dataSource(CliEnvironment environment) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(environment.url());
        dataSource.setUsername(environment.username());
        dataSource.setPassword(environment.password());
        return dataSource;
    }
}
