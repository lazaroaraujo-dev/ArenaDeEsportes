package br.com.arena.security;

/**
 * Resultado da operação de alteração de senha para controle de concorrência e rate limit.
 */
public enum Outcome {
    SUCCESS,
    FAILED,
    INFRA
}
