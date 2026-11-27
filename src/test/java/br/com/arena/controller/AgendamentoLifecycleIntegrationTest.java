package br.com.arena.controller;

import br.com.arena.enumerate.Perfil;
import br.com.arena.enumerate.StatusAgendamento;
import br.com.arena.enumerate.TipoQuadra;
import br.com.arena.exception.GlobalExceptionHandler;
import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import br.com.arena.model.Usuario;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.repository.QuadraRepository;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.JwtService;
import br.com.arena.service.AgendamentoExpirationService;
import br.com.arena.service.AgendamentoExpirationScheduler;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestUsuarioFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class AgendamentoLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgendamentoRepository agendamentoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private QuadraRepository quadraRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AgendamentoExpirationService expirationService;

    @Autowired
    private AgendamentoExpirationScheduler expirationScheduler;

    @Autowired
    private Flyway flyway;

    private TestUsuarioFixture usuarioFixture;

    private Usuario cliente;
    private Usuario outroCliente;
    private Usuario admin;
    private Quadra quadra;
    private int slot;

    @BeforeEach
    void prepararDadosIsolados() {
        assertEquals(
                "arena_flyway_test",
                jdbcTemplate.queryForObject("SELECT current_database()", String.class)
        );

        slot = 0;
        usuarioFixture = new TestUsuarioFixture(usuarioRepository, flyway);
        cliente = salvarUsuario(Perfil.CLIENTE);
        outroCliente = salvarUsuario(Perfil.CLIENTE);
        admin = salvarUsuario(Perfil.ADMIN);

        quadra = new Quadra();
        quadra.setNome("Quadra teste " + UUID.randomUUID());
        quadra.setTipo(TipoQuadra.BEACH_TENNIS);
        quadra.setAtiva(true);
        quadra = quadraRepository.saveAndFlush(quadra);
    }

    @Test
    void schedulerExpiraNoLimiteEProcessaBacklogSemAlterarOutrosEstados() {
        Agendamento antesDoLimite = criarAgendamento(StatusAgendamento.PENDENTE);
        Agendamento noLimite = criarAgendamento(StatusAgendamento.PENDENTE);
        Agendamento depoisDoLimite = criarAgendamento(StatusAgendamento.PENDENTE);
        Agendamento confirmado = criarAgendamento(StatusAgendamento.CONFIRMADO);
        Agendamento cancelado = criarAgendamento(StatusAgendamento.CANCELADO);
        Agendamento jaExpirado = criarAgendamento(StatusAgendamento.EXPIRADO);

        definirIdadeEmMinutos(antesDoLimite, 719);
        definirIdadeEmMinutos(noLimite, 720);
        definirIdadeEmMinutos(depoisDoLimite, 721);
        definirIdadeEmMinutos(confirmado, 900);
        definirIdadeEmMinutos(cancelado, 900);
        definirIdadeEmMinutos(jaExpirado, 900);

        // Simula o backlog acumulado enquanto a aplicação estava desligada.
        expirationScheduler.processarAgendamentosVencidos();

        assertEquals(StatusAgendamento.PENDENTE, statusDoAgendamento(antesDoLimite));
        assertEquals(StatusAgendamento.EXPIRADO, statusDoAgendamento(noLimite));
        assertEquals(StatusAgendamento.EXPIRADO, statusDoAgendamento(depoisDoLimite));
        assertEquals(StatusAgendamento.CONFIRMADO, statusDoAgendamento(confirmado));
        assertEquals(StatusAgendamento.CANCELADO, statusDoAgendamento(cancelado));
        assertEquals(StatusAgendamento.EXPIRADO, statusDoAgendamento(jaExpirado));
        assertEquals(0, expirationService.expirarProximoLote());
    }

    @Test
    void schedulerProcessaBacklogMaiorQueUmLote() {
        jdbcTemplate.update(
                """
                INSERT INTO agendamento
                    (data, hora_inicio, hora_fim, status, usuario_id, quadra_id)
                SELECT CURRENT_DATE + numero, TIME '07:00', TIME '08:00',
                       'PENDENTE', ?, ?
                FROM generate_series(1, 501) AS serie(numero)
                """,
                cliente.getId(),
                quadra.getId()
        );
        jdbcTemplate.update(
                """
                UPDATE agendamento
                SET criado_em = statement_timestamp() - INTERVAL '13 hours'
                WHERE usuario_id = ? AND quadra_id = ?
                """,
                cliente.getId(),
                quadra.getId()
        );

        expirationScheduler.processarAgendamentosVencidos();

        assertEquals(
                501L,
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM agendamento
                        WHERE usuario_id = ? AND quadra_id = ? AND status = 'EXPIRADO'
                        """,
                        Long.class,
                        cliente.getId(),
                        quadra.getId()
                )
        );
    }

    @Test
    void confirmacaoEhRestritaAoAdminERecusaEstadosOuPrazoVencido() throws Exception {
        Agendamento pendente = criarAgendamento(StatusAgendamento.PENDENTE);
        mockMvc.perform(patch("/agendamentos/{id}/confirmacao", pendente.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                .andExpect(jsonPath("$.criadoEm").exists());
        assertEquals(StatusAgendamento.CONFIRMADO, statusDoAgendamento(pendente));

        Agendamento deOutro = criarAgendamento(StatusAgendamento.PENDENTE);
        mockMvc.perform(patch("/agendamentos/{id}/confirmacao", deOutro.getId())
                        .header("Authorization", bearer(cliente)))
                .andExpect(status().isForbidden());
        assertEquals(StatusAgendamento.PENDENTE, statusDoAgendamento(deOutro));

        Agendamento vencido = criarAgendamento(StatusAgendamento.PENDENTE);
        definirIdadeEmMinutos(vencido, 721);
        mockMvc.perform(patch("/agendamentos/{id}/confirmacao", vencido.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        assertEquals(StatusAgendamento.EXPIRADO, statusDoAgendamento(vencido));

        Agendamento cancelado = criarAgendamento(StatusAgendamento.CANCELADO);
        Agendamento expirado = criarAgendamento(StatusAgendamento.EXPIRADO);
        mockMvc.perform(patch("/agendamentos/{id}/confirmacao", pendente.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/agendamentos/{id}/confirmacao", cancelado.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/agendamentos/{id}/confirmacao", expirado.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelamentoPreservaRegistroEValidaProprietarioEStatus() throws Exception {
        Agendamento pendente = criarAgendamento(StatusAgendamento.PENDENTE);
        mockMvc.perform(patch("/agendamentos/{id}/cancelamento", pendente.getId())
                        .header("Authorization", bearer(cliente)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADO"));
        assertEquals(StatusAgendamento.CANCELADO, statusDoAgendamento(pendente));
        assertEquals(1L, contar(pendente));

        Agendamento confirmado = criarAgendamento(StatusAgendamento.CONFIRMADO);
        mockMvc.perform(patch("/agendamentos/{id}/cancelamento", confirmado.getId())
                        .header("Authorization", bearer(cliente)))
                .andExpect(status().isOk());
        assertEquals(StatusAgendamento.CANCELADO, statusDoAgendamento(confirmado));

        Agendamento deOutro = criarAgendamento(StatusAgendamento.PENDENTE, outroCliente);
        mockMvc.perform(patch("/agendamentos/{id}/cancelamento", deOutro.getId())
                        .header("Authorization", bearer(cliente)))
                .andExpect(status().isForbidden());
        assertEquals(StatusAgendamento.PENDENTE, statusDoAgendamento(deOutro));

        mockMvc.perform(patch("/agendamentos/{id}/cancelamento", deOutro.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertEquals(StatusAgendamento.CANCELADO, statusDoAgendamento(deOutro));

        Agendamento cancelado = criarAgendamento(StatusAgendamento.CANCELADO);
        Agendamento expirado = criarAgendamento(StatusAgendamento.EXPIRADO);
        mockMvc.perform(patch("/agendamentos/{id}/cancelamento", cancelado.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/agendamentos/{id}/cancelamento", expirado.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
        assertEquals(1L, contar(cancelado));
        assertEquals(1L, contar(expirado));
    }

    @Test
    void putAplicaPermissoesDeEstadoPreservaProprietarioECriadoEm() throws Exception {
        Agendamento pendente = criarAgendamento(StatusAgendamento.PENDENTE);
        Instant criadoEmOriginal = pendente.getCriadoEm();
        LocalDate dataNova = LocalDate.now().plusDays(12);

        mockMvc.perform(put("/agendamentos/{id}", pendente.getId())
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPut(dataNova, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDENTE"));

        Agendamento atualizado = recarregar(pendente);
        assertEquals(cliente.getId(), atualizado.getUsuario().getId());
        assertEquals(criadoEmOriginal, atualizado.getCriadoEm());
        assertEquals(dataNova, atualizado.getData());

        Agendamento pendenteAdmin = criarAgendamento(StatusAgendamento.PENDENTE);
        mockMvc.perform(put("/agendamentos/{id}", pendenteAdmin.getId())
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(corpoPut(dataNova.plusDays(1), false)))
                .andExpect(status().isOk());

        Agendamento deOutro = criarAgendamento(StatusAgendamento.PENDENTE, outroCliente);
        mockMvc.perform(put("/agendamentos/{id}", deOutro.getId())
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPut(dataNova.plusDays(2), false)))
                .andExpect(status().isForbidden());

        Agendamento confirmado = criarAgendamento(StatusAgendamento.CONFIRMADO);
        mockMvc.perform(put("/agendamentos/{id}", confirmado.getId())
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPut(dataNova.plusDays(3), false)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/agendamentos/{id}", confirmado.getId())
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(corpoPut(dataNova.plusDays(3), false)))
                .andExpect(status().isOk());

        Agendamento cancelado = criarAgendamento(StatusAgendamento.CANCELADO);
        Agendamento expirado = criarAgendamento(StatusAgendamento.EXPIRADO);
        mockMvc.perform(put("/agendamentos/{id}", cancelado.getId())
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(corpoPut(dataNova.plusDays(4), false)))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/agendamentos/{id}", expirado.getId())
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(corpoPut(dataNova.plusDays(5), false)))
                .andExpect(status().isConflict());

        Agendamento pendenteVencido = criarAgendamento(StatusAgendamento.PENDENTE);
        definirIdadeEmMinutos(pendenteVencido, 721);
        mockMvc.perform(put("/agendamentos/{id}", pendenteVencido.getId())
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(corpoPut(dataNova.plusDays(6), false)))
                .andExpect(status().isConflict());
        assertEquals(StatusAgendamento.EXPIRADO, statusDoAgendamento(pendenteVencido));
    }

    @Test
    void deadlineOriginalNaoEhReiniciadoEDeleteFisicoFoiRemovido() throws Exception {
        Agendamento pendente = criarAgendamento(StatusAgendamento.PENDENTE);
        definirIdadeEmMinutos(pendente, 719);
        Instant timestampOriginal = criadoEm(pendente);

        mockMvc.perform(put("/agendamentos/{id}", pendente.getId())
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPut(LocalDate.now().plusDays(14), true)))
                .andExpect(status().isOk());

        assertEquals(timestampOriginal, criadoEm(pendente));

        Agendamento expiraNoPrazoOriginal = criarAgendamento(StatusAgendamento.PENDENTE);
        definirIdadeEmMinutos(expiraNoPrazoOriginal, 721);
        mockMvc.perform(put("/agendamentos/{id}", expiraNoPrazoOriginal.getId())
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPut(LocalDate.now().plusDays(15), false)))
                .andExpect(status().isConflict());
        assertEquals(StatusAgendamento.EXPIRADO, statusDoAgendamento(expiraNoPrazoOriginal));

        mockMvc.perform(delete("/agendamentos/{id}", pendente.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isMethodNotAllowed());
        assertEquals(1L, contar(pendente));
    }

    @Test
    void disponibilidadeUsaSomenteEstadosBloqueadoresEPermiteAdjacencia() throws Exception {
        Agendamento cancelado = criarAgendamento(StatusAgendamento.CANCELADO);
        mockMvc.perform(post("/agendamentos")
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPost(cancelado.getData(), cancelado.getHoraInicio(),
                                cancelado.getHoraFim())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDENTE"))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.nomeUsuario").value(cliente.getNome()));

        Agendamento bloqueador = criarAgendamento(StatusAgendamento.PENDENTE);
        mockMvc.perform(post("/agendamentos")
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPost(bloqueador.getData(),
                                bloqueador.getHoraInicio().plusMinutes(30),
                                bloqueador.getHoraFim().plusMinutes(30))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/agendamentos")
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPost(bloqueador.getData(), bloqueador.getHoraFim(),
                                bloqueador.getHoraFim().plusHours(1))))
                .andExpect(status().isCreated());

        Agendamento expirado = criarAgendamento(StatusAgendamento.EXPIRADO);
        expirado.setHoraInicio(LocalTime.of(11, 0));
        expirado.setHoraFim(LocalTime.NOON);
        expirado = agendamentoRepository.saveAndFlush(expirado);
        mockMvc.perform(post("/agendamentos")
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPost(expirado.getData(), expirado.getHoraInicio(),
                                expirado.getHoraFim())))
                .andExpect(status().isCreated());
    }

    @Test
    void somenteViolacaoDaExclusionConstraintEhMapeadaPara409() {
        Agendamento bloqueador = criarAgendamento(StatusAgendamento.PENDENTE);
        DataIntegrityViolationException exclusao = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO agendamento
                            (data, hora_inicio, hora_fim, status, usuario_id, quadra_id)
                        VALUES (?, ?, ?, 'PENDENTE', ?, ?)
                        """,
                        bloqueador.getData(),
                        bloqueador.getHoraInicio(),
                        bloqueador.getHoraFim(),
                        outroCliente.getId(),
                        quadra.getId()
                )
        );

        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        assertEquals(
                409,
                exceptionHandler.tratarErroGenerico(exclusao).getStatusCode().value()
        );

        DataIntegrityViolationException chaveEstrangeira = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO agendamento
                            (data, hora_inicio, hora_fim, status, usuario_id, quadra_id)
                        VALUES (?, TIME '11:00', TIME '12:00', 'PENDENTE', ?, ?)
                        """,
                        bloqueador.getData().plusDays(1),
                        -1L,
                        quadra.getId()
                )
        );
        assertEquals(
                500,
                exceptionHandler.tratarErroGenerico(chaveEstrangeira)
                        .getStatusCode()
                        .value()
        );
    }

    @Test
    void respostasExpõemTimestampEReatribuicaoDeProprietarioSegueRestritaAoAdmin()
            throws Exception {
        Agendamento agendamento = criarAgendamento(StatusAgendamento.PENDENTE);

        mockMvc.perform(put("/agendamentos/{id}", agendamento.getId())
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content(corpoPut(LocalDate.now().plusDays(16), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criadoEm").exists());

        mockMvc.perform(patch("/agendamentos/{id}/proprietario", agendamento.getId())
                        .header("Authorization", bearer(cliente))
                        .contentType("application/json")
                        .content("{\"usuarioId\":" + outroCliente.getId() + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/agendamentos/{id}/proprietario", agendamento.getId())
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content("{\"usuarioId\":" + outroCliente.getId() + "}"))
                .andExpect(status().isOk());
        assertEquals(outroCliente.getId(), recarregar(agendamento).getUsuario().getId());
    }

    private Usuario salvarUsuario(Perfil perfil) {
        String email = UUID.randomUUID() + "@example.invalid";
        String nome = "Usuário " + UUID.randomUUID();
        if (perfil == Perfil.ADMIN) {
            // arena_migrator prepara ADMIN; nunca via cadastro público nem runtime.
            return usuarioFixture.criarAdmin(nome, email, "11999999999", "hash-de-teste");
        }
        // arena_runtime cria CLIENTE pela função h04_registrar_cliente(...).
        return usuarioFixture.criarCliente(nome, email, "11999999999", "hash-de-teste");
    }

    private Agendamento criarAgendamento(StatusAgendamento status) {
        return criarAgendamento(status, cliente);
    }

    private Agendamento criarAgendamento(StatusAgendamento status, Usuario proprietario) {
        Agendamento agendamento = new Agendamento();
        agendamento.setData(LocalDate.now().plusDays(10));
        agendamento.setHoraInicio(LocalTime.of(7, 0).plusHours(slot++));
        agendamento.setHoraFim(agendamento.getHoraInicio().plusHours(1));
        agendamento.setStatus(status);
        agendamento.setUsuario(proprietario);
        agendamento.setQuadra(quadra);
        return agendamentoRepository.saveAndFlush(agendamento);
    }

    private void definirIdadeEmMinutos(Agendamento agendamento, int minutos) {
        jdbcTemplate.update(
                """
                UPDATE agendamento
                SET criado_em = statement_timestamp() - (? * INTERVAL '1 minute')
                WHERE id = ?
                """,
                minutos,
                agendamento.getId()
        );
    }

    private StatusAgendamento statusDoAgendamento(Agendamento agendamento) {
        return agendamentoRepository.findById(agendamento.getId()).orElseThrow().getStatus();
    }

    private Instant criadoEm(Agendamento agendamento) {
        Instant valor = agendamentoRepository.findById(agendamento.getId())
                .orElseThrow()
                .getCriadoEm();
        assertNotNull(valor);
        return valor;
    }

    private Agendamento recarregar(Agendamento agendamento) {
        return agendamentoRepository.findById(agendamento.getId()).orElseThrow();
    }

    private long contar(Agendamento agendamento) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agendamento WHERE id = ?",
                Long.class,
                agendamento.getId()
        );
    }

    private String bearer(Usuario usuario) {
        return "Bearer " + jwtService.generateToken(usuario);
    }

    private String corpoPut(LocalDate data, boolean camposProibidos) {
        String campos = camposProibidos
                ? ",\"usuarioId\":" + outroCliente.getId()
                    + ",\"status\":\"CANCELADO\",\"criadoEm\":\"2000-01-01T00:00:00Z\""
                : "";
        return """
                {"data":"%s","horaInicio":"13:00:00","horaFim":"14:00:00",
                 "quadraId":%d%s}
                """.formatted(data, quadra.getId(), campos);
    }

    private String corpoPost(LocalDate data, LocalTime inicio, LocalTime fim) {
        return """
                {"data":"%s","horaInicio":"%s","horaFim":"%s",
                 "usuarioId":%d,"quadraId":%d,"status":"CONFIRMADO",
                 "criadoEm":"2000-01-01T00:00:00Z"}
                """.formatted(data, inicio, fim, outroCliente.getId(), quadra.getId());
    }

}
