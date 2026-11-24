package br.com.arena.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminLifecycleDbService {

    public static final long ADMIN_LIFECYCLE_LOCK_KEY = 7409395520021L;

    private final JdbcTemplate jdbcTemplate;

    public AdminLifecycleDbService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AdminLifecycleStateSnapshot(
            long adminCount,
            boolean bootstrapConsumed,
            boolean recoveryRequired
    ) {}

    /**
     * Adquire o advisory lock transacional comum de administracao.
     * Deve ser executado dentro de uma transacao ativa. O lock e liberado automaticamente no commit ou rollback.
     */
    public void adquirirLock() {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + ADMIN_LIFECYCLE_LOCK_KEY + ")");
    }

    /**
     * Consulta o estado singleton atraves da funcao SECURITY DEFINER h04_get_admin_lifecycle_state().
     */
    public AdminLifecycleStateSnapshot obterEstadoSingleton() {
        return jdbcTemplate.queryForObject(
                "SELECT p_admin_count, p_bootstrap_consumed, p_recovery_required FROM public.h04_get_admin_lifecycle_state()",
                (rs, rowNum) -> new AdminLifecycleStateSnapshot(
                        rs.getLong("p_admin_count"),
                        rs.getBoolean("p_bootstrap_consumed"),
                        rs.getBoolean("p_recovery_required")
                )
        );
    }

    /**
     * Retorna a contagem real de usuarios com perfil ADMIN na tabela usuario.
     */
    public long contarAdminsReais() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public.usuario WHERE perfil = 'ADMIN'",
                Long.class
        );
        return count != null ? count : 0L;
    }

    /**
     * Valida sob o lock advisory a consistencia entre o singleton e a tabela de usuarios.
     * Em caso de divergencia, falha de forma segura (fail-closed) sem alterar dados.
     */
    public AdminLifecycleStateSnapshot validarConsistenciaOuFalhar() {
        AdminLifecycleStateSnapshot state = obterEstadoSingleton();
        long realCount = contarAdminsReais();

        if (state.adminCount() != realCount) {
            throw new IllegalStateException(
                    "Inconsistencia administrativa: admin_count (" + state.adminCount()
                            + ") diverge da contagem real (" + realCount + "). Operacao abortada (fail-closed)."
            );
        }

        if (!state.bootstrapConsumed() || state.recoveryRequired()) {
            throw new IllegalStateException(
                    "Inconsistencia administrativa: sistema nao esta em estado CONSUMED ativo. "
                            + "bootstrap_consumed=" + state.bootstrapConsumed()
                            + ", recovery_required=" + state.recoveryRequired()
                            + ". Operacao abortada (fail-closed)."
            );
        }

        return state;
    }

    /**
     * Registra evento de auditoria administrativa atraves da funcao SECURITY DEFINER h04_append_audit_event.
     */
    public Long registrarAuditoria(
            String operation,
            String outcome,
            String source,
            String actorType,
            Long actorUserId,
            String actorRef,
            Long targetUserId,
            String targetEmailSnapshot,
            String profileBefore,
            String profileAfter,
            String reason,
            String changeTicket,
            String requestId,
            String errorCode
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT public.h04_append_audit_event(
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                Long.class,
                operation,
                outcome,
                source,
                actorType,
                actorUserId,
                actorRef,
                targetUserId,
                targetEmailSnapshot,
                profileBefore,
                profileAfter,
                reason,
                changeTicket,
                requestId,
                errorCode
        );
    }
}
