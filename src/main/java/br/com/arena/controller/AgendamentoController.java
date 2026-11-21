package br.com.arena.controller;

import br.com.arena.dto.agendamento.AgendamentoRequestDTO;
import br.com.arena.dto.agendamento.AgendamentoResponseDTO;
import br.com.arena.dto.agendamento.AgendamentoAtualizacaoRequestDTO;
import br.com.arena.dto.agendamento.AgendamentoProprietarioRequestDTO;
import br.com.arena.service.AgendamentoService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agendamentos")
public class AgendamentoController {

    private final AgendamentoService agendamentoService;

    public AgendamentoController(AgendamentoService agendamentoService) {
        this.agendamentoService = agendamentoService;
    }

    @PreAuthorize("hasRole('ADMIN')")
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
    @Operation(
            summary = "Reagendar",
            description = """
                    PENDENTE pode ser alterado pelo proprietário ou ADMIN antes do prazo original
                    de 12 horas; CONFIRMADO somente por ADMIN. CANCELADO e EXPIRADO são terminais.
                    criadoEm e proprietário não podem ser alterados por esta operação.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Sem permissão para editar"),
            @ApiResponse(responseCode = "404", description = "Agendamento não encontrado"),
            @ApiResponse(responseCode = "409", description = "Estado ou horário em conflito")
    })
    public AgendamentoResponseDTO atualizar(
            @PathVariable Long id,
            @Valid @RequestBody AgendamentoAtualizacaoRequestDTO dto) {

        return agendamentoService.atualizar(id, dto);
    }

    @PatchMapping("/{id}/proprietario")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reatribuir proprietário (ADMIN)")
    public AgendamentoResponseDTO alterarProprietario(
            @PathVariable Long id,
            @Valid @RequestBody AgendamentoProprietarioRequestDTO dto) {

        return agendamentoService.alterarProprietarioComoAdmin(id, dto.getUsuarioId());
    }

    @PatchMapping("/{id}/confirmacao")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Confirmar agendamento",
            description = "Somente ADMIN pode confirmar um PENDENTE antes do prazo de 12 horas."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Somente ADMIN pode confirmar"),
            @ApiResponse(responseCode = "404", description = "Agendamento não encontrado"),
            @ApiResponse(responseCode = "409", description = "Estado incompatível ou prazo vencido")
    })
    public AgendamentoResponseDTO confirmar(@PathVariable Long id) {
        return agendamentoService.confirmar(id);
    }

    @PatchMapping("/{id}/cancelamento")
    @Operation(
            summary = "Cancelar agendamento",
            description = """
                    Proprietário ou ADMIN pode cancelar PENDENTE ou CONFIRMADO.
                    O registro é preservado e o horário liberado.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Sem permissão para cancelar"),
            @ApiResponse(responseCode = "404", description = "Agendamento não encontrado"),
            @ApiResponse(responseCode = "409", description = "Estado incompatível ou prazo vencido")
    })
    public AgendamentoResponseDTO cancelar(@PathVariable Long id) {
        return agendamentoService.cancelar(id);
    }
}
