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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgendamentoControllerSecurityTest {

    private static final long AGENDAMENTO_ID = 10L;
    private static final long QUADRA_ID = 20L;
    private static final long CLIENTE_ID = 30L;
    private static final long OUTRO_USUARIO_ID = 40L;

    private AgendamentoRepository agendamentoRepository;
    private UsuarioService usuarioService;
    private QuadraService quadraService;
    private Agendamento agendamento;
    private Usuario cliente;
    private Usuario outroUsuario;
    private Usuario admin;
    private Quadra quadra;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        agendamentoRepository = mock(AgendamentoRepository.class);
        usuarioService = mock(UsuarioService.class);
        quadraService = mock(QuadraService.class);

        cliente = usuario(CLIENTE_ID, Perfil.CLIENTE, "Cliente");
        outroUsuario = usuario(OUTRO_USUARIO_ID, Perfil.CLIENTE, "Outro cliente");
        admin = usuario(50L, Perfil.ADMIN, "Admin");
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
        agendamento.setUsuario(cliente);
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
        )).thenAnswer(invocation -> Optional.of(agendamento).stream().toList());
        when(agendamentoRepository.buscarPorIdParaAtualizacao(AGENDAMENTO_ID))
                .thenReturn(Optional.of(agendamento));
        when(agendamentoRepository.save(any(Agendamento.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(quadraService.buscarEntidadePorId(QUADRA_ID)).thenReturn(quadra);
        when(quadraService.buscarParaAtualizacao(QUADRA_ID)).thenReturn(quadra);
        when(usuarioService.buscarEntidadePorId(OUTRO_USUARIO_ID)).thenReturn(outroUsuario);

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
    }

    @AfterEach
    void limparAutenticacao() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void clienteAtualizaProprioAgendamentoSemAlterarProprietario() throws Exception {
        autenticarComo(cliente);

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoAtualizacao()))
                .andExpect(status().isOk());

        assertEquals(CLIENTE_ID, agendamento.getUsuario().getId());
        assertEquals(LocalTime.of(11, 0), agendamento.getHoraInicio());
        verify(agendamentoRepository).flush();
        verify(usuarioService, never()).buscarEntidadePorId(any(Long.class));
    }

    @Test
    void usuarioIdEnviadoNoPutNaoAlteraProprietario() throws Exception {
        autenticarComo(cliente);
        String corpo = corpoAtualizacao().replace(
                "\"quadraId\": " + QUADRA_ID,
                "\"quadraId\": " + QUADRA_ID + ",\n  \"usuarioId\": " + OUTRO_USUARIO_ID
        );

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isOk());

        assertEquals(CLIENTE_ID, agendamento.getUsuario().getId());
        verify(agendamentoRepository).flush();
        verify(usuarioService, never()).buscarEntidadePorId(any(Long.class));
    }

    @Test
    void clienteNaoPodeUsarOperacaoAdministrativaParaAlterarProprietario() throws Exception {
        autenticarComo(cliente);
        String corpo = """
                {"usuarioId": %d}
                """.formatted(OUTRO_USUARIO_ID);

        mockMvc.perform(patch("/agendamentos/{id}/proprietario", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isForbidden());

        assertEquals(CLIENTE_ID, agendamento.getUsuario().getId());
        verify(agendamentoRepository, never()).save(any(Agendamento.class));
        verify(usuarioService, never()).buscarEntidadePorId(any(Long.class));
    }

    @Test
    void clienteNaoPodeEditarAgendamentoDeTerceiro() throws Exception {
        agendamento.setUsuario(outroUsuario);
        autenticarComo(cliente);

        mockMvc.perform(put("/agendamentos/{id}", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpoAtualizacao()))
                .andExpect(status().isForbidden());

        assertEquals(OUTRO_USUARIO_ID, agendamento.getUsuario().getId());
        assertEquals(LocalTime.of(9, 0), agendamento.getHoraInicio());
        verify(agendamentoRepository, never()).save(any(Agendamento.class));
    }

    @Test
    void adminPodeAlterarProprietarioPelaOperacaoAdministrativaExplicita() throws Exception {
        autenticarComo(admin);
        String corpo = """
                {"usuarioId": %d}
                """.formatted(OUTRO_USUARIO_ID);

        mockMvc.perform(patch("/agendamentos/{id}/proprietario", AGENDAMENTO_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andExpect(status().isOk());

        assertEquals(OUTRO_USUARIO_ID, agendamento.getUsuario().getId());
        verify(usuarioService).buscarEntidadePorId(OUTRO_USUARIO_ID);
        verify(agendamentoRepository).flush();
    }

    private static Usuario usuario(Long id, Perfil perfil, String nome) {
        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setPerfil(perfil);
        usuario.setNome(nome);
        usuario.setEmail(nome.toLowerCase().replace(' ', '.') + "@example.com");
        return usuario;
    }

    private static void autenticarComo(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        usuario,
                        null,
                        usuario.getAuthorities()
                )
        );
    }

    private String corpoAtualizacao() {
        return """
                {
                  "data": "%s",
                  "horaInicio": "11:00:00",
                  "horaFim": "12:00:00",
                  "quadraId": %d
                }
                """.formatted(LocalDate.now().plusDays(5), QUADRA_ID);
    }
}
