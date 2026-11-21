package br.com.arena.service;

record AgendamentoOperationResult<T>(T valor, String conflito) {

    static <T> AgendamentoOperationResult<T> sucesso(T valor) {
        return new AgendamentoOperationResult<>(valor, null);
    }

    static <T> AgendamentoOperationResult<T> conflito(String mensagem) {
        return new AgendamentoOperationResult<>(null, mensagem);
    }

    boolean possuiConflito() {
        return conflito != null;
    }
}
