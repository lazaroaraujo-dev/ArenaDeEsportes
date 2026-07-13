package br.com.arena.controller;

import br.com.arena.dto.agendamento.AgendamentoRequestDTO;
import br.com.arena.dto.agendamento.AgendamentoResponseDTO;
import br.com.arena.service.AgendamentoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agendamentos")
public class AgendamentoController {

    private final AgendamentoService agendamentoService;

    public AgendamentoController(AgendamentoService agendamentoService) {
        this.agendamentoService = agendamentoService;
    }

    @GetMapping
    public List<AgendamentoResponseDTO> listarTodos() {
        return agendamentoService.listarTodos();
    }

    @GetMapping("/{id}")
    public AgendamentoResponseDTO buscarPorId(@PathVariable Long id) {
        return agendamentoService.buscarPorId(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgendamentoResponseDTO salvar(
            @Valid @RequestBody AgendamentoRequestDTO dto) {

        return agendamentoService.salvar(dto);
    }

    @PutMapping("/{id}")
    public AgendamentoResponseDTO atualizar(
            @PathVariable Long id,
            @Valid @RequestBody AgendamentoRequestDTO dto) {

        return agendamentoService.atualizar(id, dto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletar(@PathVariable Long id) {
        agendamentoService.deletar(id);
    }
}