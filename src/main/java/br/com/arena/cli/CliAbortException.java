package br.com.arena.cli;

/**
 * Exceção usada para abortar o CLI com um código de saída específico.
 */
class CliAbortException extends RuntimeException {

    private final int exitCode;

    CliAbortException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    int exitCode() {
        return exitCode;
    }
}
