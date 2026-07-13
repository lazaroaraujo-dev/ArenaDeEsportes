package br.com.arena.controller;

import br.com.arena.dto.quadra.QuadraRequestDTO;
import br.com.arena.dto.quadra.QuadraResponseDTO;
import br.com.arena.service.QuadraService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    @ResponseStatus(HttpStatus.CREATED)
    public QuadraResponseDTO salvar(@Valid @RequestBody QuadraRequestDTO dto) {
        return quadraService.salvar(dto);
    }

    @PutMapping("/{id}")
    public QuadraResponseDTO atualizar(@PathVariable Long id,
                                       @Valid @RequestBody QuadraRequestDTO dto) {
        return quadraService.atualizar(id, dto);
    }

    @PatchMapping("/{id}/status")
    public QuadraResponseDTO alterarStatus(@PathVariable Long id,
                                           @RequestParam boolean ativa) {
        return quadraService.alterarStatus(id, ativa);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletar(@PathVariable Long id) {
        quadraService.deletar(id);
    }
}