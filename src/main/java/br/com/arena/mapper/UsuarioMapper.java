package br.com.arena.mapper;

import br.com.arena.dto.usuario.UsuarioRequestDTO;
import br.com.arena.dto.usuario.UsuarioResponseDTO;
import br.com.arena.model.Usuario;

public class UsuarioMapper {

    public static Usuario toEntity(UsuarioRequestDTO dto) {

        Usuario usuario = new Usuario();

        usuario.setNome(dto.getNome());
        usuario.setEmail(dto.getEmail());
        usuario.setTelefone(dto.getTelefone());
        usuario.setSenha(dto.getSenha());

        return usuario;
    }

    public static UsuarioResponseDTO toDTO(Usuario usuario) {

        UsuarioResponseDTO dto = new UsuarioResponseDTO();

        dto.setId(usuario.getId());
        dto.setNome(usuario.getNome());
        dto.setEmail(usuario.getEmail());
        dto.setTelefone(usuario.getTelefone());
        dto.setPerfil(usuario.getPerfil());

        return dto;
    }

}