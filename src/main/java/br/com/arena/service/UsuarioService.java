package br.com.arena.service;

import br.com.arena.dto.usuario.UsuarioRequestDTO;
import br.com.arena.dto.usuario.UsuarioResponseDTO;
import br.com.arena.enumerate.Perfil;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.ResourceNotFoundException;
import br.com.arena.mapper.UsuarioMapper;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;

    public UsuarioService(UsuarioRepository usuarioRepository) {
        this.usuarioRepository = usuarioRepository;
    }

    public List<UsuarioResponseDTO> listarTodos() {

        return usuarioRepository.findAll()
                .stream()
                .map(UsuarioMapper::toDTO)
                .collect(Collectors.toList());
    }

    public UsuarioResponseDTO buscarPorId(Long id) {

        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Usuário não encontrado."));

        return UsuarioMapper.toDTO(usuario);
    }

    public Usuario buscarEntidadePorId(Long id) {

        return usuarioRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Usuário não encontrado."));
    }

    public UsuarioResponseDTO salvar(UsuarioRequestDTO dto) {

        if (usuarioRepository.findByEmail(dto.getEmail()).isPresent()) {
            throw new BusinessException("E-mail já cadastrado.");
        }

        Usuario usuario = UsuarioMapper.toEntity(dto);

        usuario.setPerfil(Perfil.CLIENTE);
        usuario = usuarioRepository.save(usuario);

        return UsuarioMapper.toDTO(usuario);
    }

    public UsuarioResponseDTO atualizar(Long id, UsuarioRequestDTO dto) {

        Usuario usuario = buscarEntidadePorId(id);

        if (!usuario.getEmail().equals(dto.getEmail())
                && usuarioRepository.findByEmail(dto.getEmail()).isPresent()) {

            throw new BusinessException("E-mail já cadastrado.");
        }

        usuario.setNome(dto.getNome());
        usuario.setEmail(dto.getEmail());
        usuario.setTelefone(dto.getTelefone());
        usuario.setSenha(dto.getSenha());
        usuario.setPerfil(dto.getPerfil());

        usuario = usuarioRepository.save(usuario);

        return UsuarioMapper.toDTO(usuario);
    }

    public void deletar(Long id) {

        Usuario usuario = buscarEntidadePorId(id);

        if (!usuario.getAgendamentos().isEmpty()) {
            throw new BusinessException(
                    "Não é possível excluir um usuário que possui agendamentos."
            );
        }

        usuarioRepository.delete(usuario);
    }

}