package br.com.arena.service;

/**
 * Status retornado por {@code public.h04_admin_alterar_perfil(...)}.
 *
 * <p>Qualquer valor desconhecido ou nulo é tratado como falha fechada.</p>
 */
public enum AdminWriterProfileStatus {
    SUCCESS,
    NO_OP,
    TARGET_NOT_FOUND,
    LAST_ADMIN;

    static AdminWriterProfileStatus fromDatabase(String value) {
        if (value == null) {
            throw new IllegalStateException("Admin writer returned a null profile status.");
        }

        return switch (value) {
            case "SUCCESS" -> SUCCESS;
            case "NO_OP" -> NO_OP;
            case "TARGET_NOT_FOUND" -> TARGET_NOT_FOUND;
            case "LAST_ADMIN" -> LAST_ADMIN;
            default -> throw new IllegalStateException(
                    "Admin writer returned an unknown profile status: " + value
            );
        };
    }
}
