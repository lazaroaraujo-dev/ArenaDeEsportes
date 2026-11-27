package br.com.arena.service;

/**
 * Status retornado por {@code public.h04_admin_excluir_usuario(...)}.
 *
 * <p>Qualquer valor desconhecido é tratado como falha fechada, nunca como
 * sucesso.</p>
 */
public enum AdminWriterDeleteStatus {
    SUCCESS,
    TARGET_NOT_FOUND,
    LAST_ADMIN,
    HAS_AGENDAMENTOS;

    static AdminWriterDeleteStatus fromDatabase(String value) {
        if (value == null) {
            throw new IllegalStateException("Admin writer returned a null status.");
        }

        return switch (value) {
            case "SUCCESS" -> SUCCESS;
            case "TARGET_NOT_FOUND" -> TARGET_NOT_FOUND;
            case "LAST_ADMIN" -> LAST_ADMIN;
            case "HAS_AGENDAMENTOS" -> HAS_AGENDAMENTOS;
            default -> throw new IllegalStateException(
                    "Admin writer returned an unknown status: " + value
            );
        };
    }
}
