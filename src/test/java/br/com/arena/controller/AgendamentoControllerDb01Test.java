package br.com.arena.controller;

import br.com.arena.enumerate.Perfil;
import br.com.arena.enumerate.StatusAgendamento;
import br.com.arena.exception.GlobalExceptionHandler;
import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import br.com.arena.model.Usuario;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.service.AgendamentoService;
import br.com.arena.service.AgendamentoWriteService;
import br.com.arena.service.PostgresClock;
import br.com.arena.service.QuadraService;
import br.com.arena.service.UsuarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgendamentoControllerDb01Test {

    private static final long AGENDAMENTO_ID = 100L;
    private static final long QUADRA_ID = 200L;
    private static final long USUARIO_ID = 300L;

    private AgendamentoRepository agendamentoRepository;
    private Agendamento agendamento;
    private Quadra quadra;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        agendamentoRepository = mock(AgendamentoRepository.class);
        UsuarioService usuarioService = mock(UsuarioService.class);
        QuadraService quadraService = mock(QuadraService.class);

        Usuario usuario = new Usuario();
        usuario.setId(USUARIO_ID);
        usuario.setNome("Cliente");
        usuario.setEmail("cliente@example.com");
        usuario.setPerfil(Perfil.CLIENTE);

        quadra = new Quadra();
        quadra.setId(QUADRA_ID);
        quadra.setNome("Quadra A");
        quadra.setAtiva(true);

        agendamento = new Agendamento();
        agendamento.setId(AGENDAMENTO_ID);
        agendamento.setData(LocalDate.now().plusDays(5));
        agendamento.setHoraInicio(LocalTime.of(9, 0));
        agendamento.setHoraFim(LocalTime.of(10, 0));
        agendamento.setStatus(StatusAgendamento.PENDENTE);
        agendamento.setUsuario(usuario);
        agendamento.setQuadra(quadra);
        ReflectionTestUtils.setField(
                agendamento,
                "criadoEm",
                Instant.now().minusSeconds(60)
        );

        when(agendamentoRepository.findById(AGENDAMENTO_ID))
                .thenReturn(Optional.of(agendamento));
        when(agendamentoRepository.buscarAlvoEBloqueantesParaAtualizacao(
                eq(AGENDAMENTO_ID),
                eq(QUADRA_ID),
                any(LocalDate.class),
                anyCollection()
        )).thenAnswer(invocation -> List.of(agendamento));
        when(agendamentoRepository.save(any(Agendamento.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(quadraService.buscarEntidadePorId(QUADRA_ID)).thenReturn(quadra);
        when(quadraService.buscarParaAtualizacao(QUADRA_ID)).thenReturn(quadra);

        PostgresClock postgresClock = mock(PostgresClock.class);
        when(postgresClock.agora()).thenReturn(Instant.now());
        AgendamentoWriteService writeService = new AgendamentoWriteService(
                agendamentoRepository,
                usuarioService,
                quadraService,
                postgresClock
        );
        AgendamentoService service =
                new AgendamentoService(agendamentoRepository, writeService);

        mockMvc = MockMvcBuilders.standaloneSetup(new AgendamentoController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        usuario,
                        null,
                        usuario.getAuthorities()
                )
        );
    }

    @AfterEach
    void limparAutenticacao() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void atualizaParaHorarioDisponivel() throws Exception {
        when(agendamentoRepository.findByQuadraAndData(quadra, agendamento.getData()))
                .thenReturn(List.of(agendamento));

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoAtualizacao(agendamento.getData(), "11:00:00", "12:00:00")))
                .andExpect(status().isOk());

        assertEquals(LocalTime.of(11, 0), agendamento.getHoraInicio());
        assertEquals(LocalTime.of(12, 0), agendamento.getHoraFim());
        verify(agendamentoRepository).flush();
    }

    @Test
    void rejeitaAtualizacaoParaHorarioOcupado() throws Exception {
        Agendamento conflito = agendamento(101L, agendamento.getData(),
                LocalTime.of(11, 0), LocalTime.NOON, quadra);
        when(agendamentoRepository.findByQuadraAndData(quadra, agendamento.getData()))
                .thenReturn(List.of(agendamento, conflito));

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoAtualizacao(agendamento.getData(), "11:00:00", "12:00:00")))
                .andExpect(status().isConflict());

        verify(agendamentoRepository, never()).save(any(Agendamento.class));
    }

    @Test
    void permiteAtualizacaoMantendoMesmoHorarioAoIgnorarOProprioAgendamento() throws Exception {
        when(agendamentoRepository.findByQuadraAndData(quadra, agendamento.getData()))
                .thenReturn(List.of(agendamento));

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoAtualizacao(agendamento.getData(), "09:00:00", "10:00:00")))
                .andExpect(status().isOk());

        assertEquals(LocalTime.of(9, 0), agendamento.getHoraInicio());
        assertEquals(LocalTime.of(10, 0), agendamento.getHoraFim());
        verify(agendamentoRepository).flush();
    }

    @Test
    void rejeitaAtualizacaoParaQuadraInativa() throws Exception {
        quadra.setAtiva(false);

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoAtualizacao(agendamento.getData(), "11:00:00", "12:00:00")))
                .andExpect(status().isBadRequest());

        verify(agendamentoRepository, never())
                .findByQuadraAndData(any(Quadra.class), any(LocalDate.class));
        verify(agendamentoRepository, never()).save(any(Agendamento.class));
    }

    @Test
    void conflitoNaoAlteraOsDadosOriginaisDoAgendamento() throws Exception {
        LocalDate dataOriginal = agendamento.getData();
        LocalDate dataSolicitada = dataOriginal.plusDays(1);
        LocalTime inicioOriginal = agendamento.getHoraInicio();
        LocalTime fimOriginal = agendamento.getHoraFim();
        Quadra quadraOriginal = agendamento.getQuadra();
        Agendamento conflito = agendamento(102L, dataSolicitada,
                LocalTime.of(15, 0), LocalTime.of(16, 0), quadra);
        when(agendamentoRepository.findByQuadraAndData(quadra, dataSolicitada))
                .thenReturn(List.of(conflito));

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoAtualizacao(dataSolicitada, "15:30:00", "16:30:00")))
                .andExpect(status().isConflict());

        assertEquals(dataOriginal, agendamento.getData());
        assertEquals(inicioOriginal, agendamento.getHoraInicio());
        assertEquals(fimOriginal, agendamento.getHoraFim());
        assertSame(quadraOriginal, agendamento.getQuadra());
        verify(agendamentoRepository, never()).save(any(Agendamento.class));
    }

    private static Agendamento agendamento(
            Long id,
            LocalDate data,
            LocalTime inicio,
            LocalTime fim,
            Quadra quadra) {
        Agendamento agendamento = new Agendamento();
        agendamento.setId(id);
        agendamento.setData(data);
        agendamento.setHoraInicio(inicio);
        agendamento.setHoraFim(fim);
        agendamento.setStatus(StatusAgendamento.PENDENTE);
        agendamento.setQuadra(quadra);
        return agendamento;
    }

    private String corpoAtualizacao(LocalDate data, String inicio, String fim) {
        return """
                {
                  "data": "%s",
                  "horaInicio": "%s",
                  "horaFim": "%s",
                  "quadraId": %d
                }
                """.formatted(data, inicio, fim, QUADRA_ID);
    }
}
