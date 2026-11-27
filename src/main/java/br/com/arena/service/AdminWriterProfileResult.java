package br.com.arena.service;

/**
 * Resultado da função {@code public.h04_admin_alterar_perfil(...)}.
 *
 * <p>Os snapshots de perfil e e-mail são lidos pela função antes da mutação e
 * servem apenas para contexto. A tradução para o contrato HTTP pertence ao
 * serviço de aplicação.</p>
 */
public record AdminWriterProfileResult(
        AdminWriterProfileStatus status,
        String profileBefore,
        String profileAfter,
        String targetEmail
) {
}
