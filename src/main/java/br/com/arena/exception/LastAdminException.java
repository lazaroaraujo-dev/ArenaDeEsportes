package br.com.arena.exception;

/**
 * Rejeição funcional para tentativas de exclusão ou rebaixamento do último administrador (H-04).
 * Estende BusinessException para manter retrocompatibilidade com chamadores de regras de negócio,
 * permitindo política transacional granular que não descarta a auditoria de segurança.
 */
public class LastAdminException extends BusinessException {

    public LastAdminException(String message) {
        super(message);
    }
}
