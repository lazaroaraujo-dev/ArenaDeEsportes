package br.com.arena.service;

/**
 * Resultado da função {@code public.h04_admin_excluir_usuario(...)}.
 *
 * <p>{@code profileBefore} e {@code targetEmail} são snapshots lidos antes da
 * exclusão e servem apenas para contexto/auditoria. A tradução para HTTP e para
 * as exceções de domínio pertence à etapa de integração com o serviço.</p>
 */
public record AdminWriterDeleteResult(
        AdminWriterDeleteStatus status,
        String profileBefore,
        String targetEmail
) {
}
