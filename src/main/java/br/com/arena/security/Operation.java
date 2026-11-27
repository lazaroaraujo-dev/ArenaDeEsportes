package br.com.arena.security;

import java.util.Objects;

/**
 * Operação ativa de alteração de senha associada a um endereço IP e timestamp de início.
 */
record Operation(String ip, long startedAt) {
    Operation {
        Objects.requireNonNull(ip, "ip não pode ser nulo");
    }
}
