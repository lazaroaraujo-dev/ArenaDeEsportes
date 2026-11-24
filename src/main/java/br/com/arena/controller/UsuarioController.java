package br.com.arena.controller;

import br.com.arena.dto.usuario.*;
import br.com.arena.exception.ConflictException;
import br.com.arena.exception.ResourceNotFoundException;
import br.com.arena.model.Usuario;
import br.com.arena.security.SecurityUtils;
import br.com.arena.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UsuarioResponseDTO> listarTodos() {
        return usuarioService.listarTodos();
    }

    @GetMapping("/me")
    public UsuarioResponseDTO meuPerfil() {
        Usuario usuarioLogado = SecurityUtils.getUsuarioLogado();
        return usuarioService.buscarPorId(usuarioLogado.getId());
    }

    @PutMapping("/me")
    public UsuarioResponseDTO atualizarMeuPerfil(
            @Valid @RequestBody UsuarioUpdateDTO dto) {

        Usuario usuarioLogado = SecurityUtils.getUsuarioLogado();
        return usuarioService.atualizarDadosPessoais(usuarioLogado.getId(), dto);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponseDTO buscarPorId(@PathVariable Long id) {
        return usuarioService.buscarPorId(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioResponseDTO salvar(@Valid @RequestBody UsuarioCadastroDTO dto) {
        return usuarioService.salvar(dto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponseDTO atualizar(@PathVariable Long id,
                                        @Valid @RequestBody UsuarioUpdateDTO dto) {
        return usuarioService.atualizarDadosPessoais(id, dto);
    }

    @PatchMapping("/{id}/perfil")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UsuarioResponseDTO> alterarPerfil(
            @PathVariable Long id,
            @Valid @RequestBody UsuarioPerfilDTO dto) {

        AdminOperationResult result = usuarioService.alterarPerfil(id, dto);

        if (result.status() == AdminOperationStatus.TARGET_NOT_FOUND) {
            throw new ResourceNotFoundException(result.message());
        }
        if (result.status() == AdminOperationStatus.LAST_ADMIN) {
            throw new ConflictException(result.message());
        }

        return ResponseEntity.ok(result.usuario());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletar(@PathVariable Long id) {
        AdminOperationResult result = usuarioService.deletarAdministrativo(id);

        if (result.status() == AdminOperationStatus.TARGET_NOT_FOUND) {
            throw new ResourceNotFoundException(result.message());
        }
        if (result.status() == AdminOperationStatus.LAST_ADMIN) {
            throw new ConflictException(result.message());
        }
    }
}
