package br.com.arena.controller;

import br.com.arena.dto.usuario.UsuarioRequestDTO;
import br.com.arena.dto.usuario.UsuarioResponseDTO;
import br.com.arena.model.Usuario;
import br.com.arena.security.SecurityUtils;
import br.com.arena.service.UsuarioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
            @Valid @RequestBody UsuarioRequestDTO dto) {

        Usuario usuarioLogado = SecurityUtils.getUsuarioLogado();
        return usuarioService.atualizarProprioPerfil(usuarioLogado.getId(), dto);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponseDTO buscarPorId(@PathVariable Long id) {
        return usuarioService.buscarPorId(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UsuarioResponseDTO salvar(@Valid @RequestBody UsuarioRequestDTO dto) {
        return usuarioService.salvar(dto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UsuarioResponseDTO atualizar(@PathVariable Long id,
                                        @Valid @RequestBody UsuarioRequestDTO dto) {
        return usuarioService.atualizar(id, dto);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletar(@PathVariable Long id) {
        usuarioService.deletar(id);
    }
}