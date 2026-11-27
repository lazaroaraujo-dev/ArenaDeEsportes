package br.com.arena.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Executa exclusivamente as funções SECURITY DEFINER
 * {@code public.h04_admin_excluir_usuario(...)} e
 * {@code public.h04_admin_alterar_perfil(...)} usando o datasource de
 * {@code arena_admin_writer}.
 *
 * <p>Esta classe não executa DML direto, não usa {@code SET ROLE} e não depende
 * de membership entre roles. A função no banco é a unidade atômica: advisory
 * lock, validação do ator/estado, checagem de agendamentos, proteção do último
 * ADMIN, {@code DELETE} e auditoria acontecem na mesma transação PostgreSQL.</p>
 *
 * <p>O {@link TransactionTemplate} próprio, com
 * {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}, garante que a
 * chamada seja commitada antes de a aplicação traduzir o status retornado para
 * exceções HTTP. Assim, rejeições auditadas ({@code LAST_ADMIN} e
 * {@code HAS_AGENDAMENTOS}) permanecem persistidas mesmo que a camada chamadora
 * lance uma exceção depois.</p>
 */
public class AdminWriterJdbcService implements AutoCloseable {

    static final String EXCLUIR_USUARIO_SQL = """
            SELECT status, profile_before, target_email
            FROM public.h04_admin_excluir_usuario(
                ?::bigint, ?::bigint, ?::varchar, ?::text, ?::varchar, ?::varchar
            )
            """;

    static final String ALTERAR_PERFIL_SQL = """
            SELECT status, profile_before, profile_after, target_email
            FROM public.h04_admin_alterar_perfil(
                ?::bigint, ?::bigint, ?::varchar, ?::varchar, ?::text, ?::varchar, ?::varchar
            )
            """;

    private static final int TRANSACTION_TIMEOUT_SECONDS = 15;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;
    private final Runnable closeAction;

    public AdminWriterJdbcService(DataSource dataSource) {
        this(
                new JdbcTemplate(dataSource),
                transactionOperations(dataSource),
                closeAction(dataSource)
        );
    }

    AdminWriterJdbcService(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactionOperations,
            Runnable closeAction) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionOperations = transactionOperations;
        this.closeAction = closeAction;
    }

    /**
     * Executa a exclusão administrativa em uma transação própria do writer.
     *
     * @param actorUserId identidade do operador autenticado, fornecida pela
     *                    aplicação a partir do SecurityContext; nunca deve vir
     *                    de input direto do cliente.
     */
    public AdminWriterDeleteResult excluirUsuario(
            long actorUserId,
            long targetUserId,
            String actorRef,
            String reason,
            String changeTicket,
            String requestId) {
        AdminWriterDeleteResult result = transactionOperations.execute(status ->
                jdbcTemplate.queryForObject(
                        EXCLUIR_USUARIO_SQL,
                        AdminWriterJdbcService::mapRow,
                        actorUserId,
                        targetUserId,
                        actorRef,
                        reason,
                        changeTicket,
                        requestId
                )
        );

        if (result == null) {
            throw new IllegalStateException(
                    "Admin writer delete function returned no result row."
            );
        }

        return result;
    }

    /**
     * Executa a alteração administrativa de perfil na mesma transação isolada do
     * writer usada pela exclusão. O id do ator deve vir do principal autenticado;
     * a função revalida o ator sob o advisory lock.
     */
    public AdminWriterProfileResult alterarPerfil(
            long actorUserId,
            long targetUserId,
            String novoPerfil,
            String actorRef,
            String reason,
            String changeTicket,
            String requestId) {
        AdminWriterProfileResult result = transactionOperations.execute(status ->
                jdbcTemplate.queryForObject(
                        ALTERAR_PERFIL_SQL,
                        AdminWriterJdbcService::mapProfileRow,
                        actorUserId,
                        targetUserId,
                        novoPerfil,
                        actorRef,
                        reason,
                        changeTicket,
                        requestId
                )
        );

        if (result == null) {
            throw new IllegalStateException(
                    "Admin writer profile function returned no result row."
            );
        }

        return result;
    }

    static AdminWriterDeleteResult mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new AdminWriterDeleteResult(
                AdminWriterDeleteStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getString("profile_before"),
                resultSet.getString("target_email")
        );
    }

    static AdminWriterProfileResult mapProfileRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new AdminWriterProfileResult(
                AdminWriterProfileStatus.fromDatabase(resultSet.getString("status")),
                resultSet.getString("profile_before"),
                resultSet.getString("profile_after"),
                resultSet.getString("target_email")
        );
    }

    @Override
    public void close() {
        closeAction.run();
    }

    private static TransactionOperations transactionOperations(DataSource dataSource) {
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        transactionManager.setNestedTransactionAllowed(false);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        return template;
    }

    private static Runnable closeAction(DataSource dataSource) {
        return () -> {
            if (dataSource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ex) {
                    throw new IllegalStateException(
                            "Failed to close the admin writer datasource.", ex
                    );
                }
            }
        };
    }
}
