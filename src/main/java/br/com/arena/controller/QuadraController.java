package br.com.arena.controller;

import br.com.arena.dto.quadra.QuadraRequestDTO;
import br.com.arena.dto.quadra.QuadraResponseDTO;
import br.com.arena.service.QuadraService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/quadras")
public class QuadraController {

    private final QuadraService quadraService;

    public QuadraController(QuadraService quadraService) {
        this.quadraService = quadraService;
    }

    @GetMapping
    public List<QuadraResponseDTO> listarTodas() {
        return quadraService.listarTodas();
    }

    @GetMapping("/{id}")
    public QuadraResponseDTO buscarPorId(@PathVariable Long id) {
        return quadraService.buscarPorId(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public QuadraResponseDTO salvar(@Valid @RequestBody QuadraRequestDTO dto) {
        return quadraService.salvar(dto);
    }
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public QuadraResponseDTO atualizar(@PathVariable Long id, @Valid @RequestBody QuadraRequestDTO dto) {
        return quadraService.atualizar(id, dto);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public QuadraResponseDTO alterarStatus(@PathVariable Long id,
                                           @RequestParam boolean ativa) {
        return quadraService.alterarStatus(id, ativa);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletar(@PathVariable Long id) {
        quadraService.deletar(id);
    }
}