package br.com.arena.dto.usuario;

public record AdminOperationResult(
        AdminOperationStatus status,
        UsuarioResponseDTO usuario,
        String message
) {}
