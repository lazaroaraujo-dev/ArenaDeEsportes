package br.com.arena.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWriterJdbcServiceTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void executesAllowlistedFunctionWithAllSixParametersInsideItsOwnTransaction() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TransactionOperations transactionOperations = mock(TransactionOperations.class);
        AtomicBoolean transactionUsed = new AtomicBoolean(false);

        AdminWriterDeleteResult expected = new AdminWriterDeleteResult(
                AdminWriterDeleteStatus.SUCCESS,
                "CLIENTE",
                "target@example.invalid"
        );

        when(transactionOperations.execute(any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    transactionUsed.set(true);
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(
                            mock(org.springframework.transaction.TransactionStatus.class)
                    );
                });

        when(jdbcTemplate.queryForObject(
                eq(AdminWriterJdbcService.EXCLUIR_USUARIO_SQL),
                any(RowMapper.class),
                any(), any(), any(), any(), any(), any()
        )).thenReturn(expected);

        AdminWriterJdbcService service = new AdminWriterJdbcService(
                jdbcTemplate,
                transactionOperations,
                () -> {
                }
        );

        AdminWriterDeleteResult result = service.excluirUsuario(
                1L,
                2L,
                "admin@example.invalid",
                "motivo",
                "TICKET-1",
                "REQUEST-1"
        );

        assertSame(expected, result);
        assertTrue(transactionUsed.get());
        verify(transactionOperations).execute(any(TransactionCallback.class));
        verify(jdbcTemplate).queryForObject(
                eq(AdminWriterJdbcService.EXCLUIR_USUARIO_SQL),
                any(RowMapper.class),
                eq(1L),
                eq(2L),
                eq("admin@example.invalid"),
                eq("motivo"),
                eq("TICKET-1"),
                eq("REQUEST-1")
        );
    }

    @Test
    void usesRequiresNewAndReadCommittedTransactionForWriterDataSource() {
        AdminWriterJdbcService service = new AdminWriterJdbcService(mock(DataSource.class));

        TransactionTemplate template = (TransactionTemplate) ReflectionTestUtils.getField(
                service,
                "transactionOperations"
        );

        assertNotNull(template);
        assertEquals(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                template.getPropagationBehavior()
        );
        assertEquals(
                TransactionDefinition.ISOLATION_READ_COMMITTED,
                template.getIsolationLevel()
        );
        assertEquals(15, template.getTimeout());
    }

    @Test
    void sqlOnlyCallsTheAllowlistedSecurityDefinerFunction() {
        String sql = AdminWriterJdbcService.EXCLUIR_USUARIO_SQL.toUpperCase();

        assertTrue(sql.strip().startsWith("SELECT"));
        assertTrue(sql.contains("PUBLIC.H04_ADMIN_EXCLUIR_USUARIO"));
        assertFalse(sql.contains("DELETE "));
        assertFalse(sql.contains("UPDATE "));
        assertFalse(sql.contains("INSERT "));
        assertFalse(sql.contains("SET ROLE"));
        assertFalse(sql.contains("SET LOCAL ROLE"));
    }

    @Test
    void mapsEveryStatusReturnedByTheDatabase() throws SQLException {
        for (String rawStatus : new String[]{
                "SUCCESS", "TARGET_NOT_FOUND", "LAST_ADMIN", "HAS_AGENDAMENTOS"}) {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("status")).thenReturn(rawStatus);
            when(resultSet.getString("profile_before")).thenReturn("ADMIN");
            when(resultSet.getString("target_email")).thenReturn("target@example.invalid");

            AdminWriterDeleteResult result = AdminWriterJdbcService.mapRow(resultSet, 0);

            assertEquals(rawStatus, result.status().name());
            assertEquals("ADMIN", result.profileBefore());
            assertEquals("target@example.invalid", result.targetEmail());
        }
    }

    @Test
    void rejectsUnknownOrNullStatus() throws SQLException {
        ResultSet unknown = mock(ResultSet.class);
        when(unknown.getString("status")).thenReturn("UNEXPECTED");

        assertThrows(
                IllegalStateException.class,
                () -> AdminWriterJdbcService.mapRow(unknown, 0)
        );

        ResultSet missing = mock(ResultSet.class);
        when(missing.getString("status")).thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> AdminWriterJdbcService.mapRow(missing, 0)
        );
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rejectsMissingResultRow() {
        TransactionOperations transactionOperations = mock(TransactionOperations.class);
        when(transactionOperations.execute(any(TransactionCallback.class))).thenReturn(null);

        AdminWriterJdbcService service = new AdminWriterJdbcService(
                mock(JdbcTemplate.class),
                transactionOperations,
                () -> {
                }
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.excluirUsuario(1L, 2L, null, null, null, null)
        );
    }

    @Test
    void closeDelegatesToTheConfiguredCloseAction() {
        AtomicBoolean closed = new AtomicBoolean(false);

        AdminWriterJdbcService service = new AdminWriterJdbcService(
                mock(JdbcTemplate.class),
                mock(TransactionOperations.class),
                () -> closed.set(true)
        );

        service.close();

        assertTrue(closed.get());
    }
}
