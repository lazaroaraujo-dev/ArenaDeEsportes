package br.com.arena.dto.usuario;

import br.com.arena.enumerate.Perfil;
import lombok.Data;

@Data
public class UsuarioResponseDTO {

    private Long id;

    private String nome;

    private String email;

    private String telefone;

    private Perfil perfil;
}