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
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class AgendamentoConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgendamentoRepository agendamentoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private QuadraRepository quadraRepository;

    @Autowired
    private AgendamentoExpirationService expirationService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private Usuario proprietario;
    private Usuario outroCliente;
    private Usuario admin;
    private Quadra quadra;

    @BeforeEach
    void prepararFixture() {
        assertEquals(
                "arena_flyway_test",
                jdbcTemplate.queryForObject("SELECT current_database()", String.class)
        );
        proprietario = criarUsuario(Perfil.CLIENTE);
        outroCliente = criarUsuario(Perfil.CLIENTE);
        admin = criarUsuario(Perfil.ADMIN);

        quadra = new Quadra();
        quadra.setNome("Quadra concorrência " + UUID.randomUUID());
        quadra.setTipo(TipoQuadra.BEACH_TENNIS);
        quadra.setAtiva(true);
        quadra = quadraRepository.saveAndFlush(quadra);
    }

    @Test
    void confirmacaoBloqueadaConsultaEstadoERelogioDoBancoDepoisDoLock() throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch tentativaIniciada = new CountDownLatch(1);
        AtomicReference<Future<Integer>> respostaHttp = new AtomicReference<>();

        try {
            TransactionTemplate holder = new TransactionTemplate(transactionManager);
            holder.execute(status -> {
                agendamentoRepository.buscarPorIdParaAtualizacao(agendamento.getId())
                        .orElseThrow();

                respostaHttp.set(executor.submit(() -> {
                    tentativaIniciada.countDown();
                    return confirmar(agendamento.getId());
                }));
                aguardar(tentativaIniciada);
                assertTrue(aguardarLockNoPostgres(Duration.ofSeconds(10)));

                // O horário de criação passa o deadline enquanto a outra conexão espera.
                jdbcTemplate.update(
                        """
                        UPDATE agendamento
                        SET criado_em = clock_timestamp() - INTERVAL '13 hours'
                        WHERE id = ?
                        """,
                        agendamento.getId()
                );
                return null;
            });

            assertEquals(409, respostaHttp.get().get(10, TimeUnit.SECONDS));
            assertEquals(StatusAgendamento.EXPIRADO, statusNoBanco(agendamento));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirmacaoRespeitaPrazoAntesNoLimiteEDepois() throws Exception {
        Agendamento antes = criarAgendamento(LocalTime.of(9, 0));
        Agendamento limite = criarAgendamento(LocalTime.of(10, 0));
        Agendamento depois = criarAgendamento(LocalTime.of(11, 0));
        definirIdadeEmMinutos(antes, 660);
        definirIdadeEmMinutos(limite, 720);
        definirIdadeEmMinutos(depois, 780);

        assertEquals(200, confirmar(antes.getId()));
        assertEquals(StatusAgendamento.CONFIRMADO, statusNoBanco(antes));

        assertEquals(409, confirmar(limite.getId()));
        assertEquals(StatusAgendamento.EXPIRADO, statusNoBanco(limite));

        assertEquals(409, confirmar(depois.getId()));
        assertEquals(StatusAgendamento.EXPIRADO, statusNoBanco(depois));
    }

    @Test
    void confirmacaoConcorrenteComSchedulerTerminaExpiradaDepoisDoDeadline()
            throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        definirIdadeEmMinutos(agendamento, 780);

        List<Integer> resultados = executarSimultaneamente(
                () -> confirmar(agendamento.getId()),
                () -> expirationService.expirarProximoLote()
        );

        assertEquals(409, resultados.get(0));
        assertTrue(resultados.get(1) >= 0);
        assertEquals(StatusAgendamento.EXPIRADO, statusNoBanco(agendamento));
    }

    @Test
    void cancelamentoConcorrenteComExpiracaoNaoPodeSerSobrescrito() throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        definirIdadeEmMinutos(agendamento, 780);

        List<Integer> resultados = executarSimultaneamente(
                () -> cancelar(agendamento.getId(), proprietario),
                () -> expirationService.expirarProximoLote()
        );

        assertEquals(409, resultados.get(0));
        assertTrue(resultados.get(1) >= 0);
        assertEquals(StatusAgendamento.EXPIRADO, statusNoBanco(agendamento));
    }

    @Test
    void confirmacaoECancelamentoSimultaneosSaoSerializadosPeloLockDaLinha()
            throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));

        List<Integer> resultados = executarSimultaneamente(
                () -> confirmar(agendamento.getId()),
                () -> cancelar(agendamento.getId(), proprietario)
        );

        assertTrue(resultados.get(0) == 200 || resultados.get(0) == 409);
        assertEquals(200, resultados.get(1));
        assertEquals(StatusAgendamento.CANCELADO, statusNoBanco(agendamento));
    }

    @Test
    void doisPutNoMesmoRegistroSaoSerializadosSemAlterarPrazoOuProprietario()
            throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        Instant criadoEmOriginal = agendamento.getCriadoEm();
        LocalDate dataA = LocalDate.now().plusDays(12);
        LocalDate dataB = LocalDate.now().plusDays(13);

        List<Integer> resultados = executarSimultaneamente(
                () -> atualizar(agendamento.getId(), dataA, "14:00:00", "15:00:00"),
                () -> atualizar(agendamento.getId(), dataB, "16:00:00", "17:00:00")
        );

        assertEquals(List.of(200, 200), resultados);
        Agendamento estadoFinal = agendamentoRepository.findById(agendamento.getId())
                .orElseThrow();
        assertTrue(
                (estadoFinal.getData().equals(dataA)
                        && estadoFinal.getHoraInicio().equals(LocalTime.of(14, 0)))
                        || (estadoFinal.getData().equals(dataB)
                        && estadoFinal.getHoraInicio().equals(LocalTime.of(16, 0)))
        );
        assertEquals(StatusAgendamento.PENDENTE, estadoFinal.getStatus());
        assertEquals(criadoEmOriginal, estadoFinal.getCriadoEm());
        assertEquals(proprietario.getId(), estadoFinal.getUsuario().getId());
    }

    @Test
    void reatribuicaoAdminConcorrenteComPutNaoPerdeNenhumaAlteracao() throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));

        List<Integer> resultados = executarSimultaneamente(
                () -> atualizar(agendamento.getId(), LocalDate.now().plusDays(12),
                        "14:00:00", "15:00:00"),
                () -> reatribuir(agendamento.getId(), outroCliente)
        );

        assertEquals(List.of(200, 200), resultados);
        Agendamento estadoFinal = agendamentoRepository.findById(agendamento.getId())
                .orElseThrow();
        assertEquals(LocalDate.now().plusDays(12), estadoFinal.getData());
        assertEquals(LocalTime.of(14, 0), estadoFinal.getHoraInicio());
        assertEquals(outroCliente.getId(), estadoFinal.getUsuario().getId());
    }

    @Test
    void putContrapostosBloqueiamMesmasLinhasEmOrdemDeterministica() throws Exception {
        Agendamento primeira = criarAgendamento(LocalTime.of(9, 0));
        Agendamento segunda = criarAgendamento(LocalTime.of(11, 0));

        List<Integer> resultados = executarSimultaneamente(
                () -> atualizar(primeira.getId(), primeira.getData(),
                        "11:00:00", "12:00:00"),
                () -> atualizar(segunda.getId(), segunda.getData(),
                        "09:00:00", "10:00:00")
        );

        assertEquals(List.of(409, 409), resultados);
        Agendamento primeiraPersistida =
                agendamentoRepository.findById(primeira.getId()).orElseThrow();
        Agendamento segundaPersistida =
                agendamentoRepository.findById(segunda.getId()).orElseThrow();
        assertEquals(LocalTime.of(9, 0), primeiraPersistida.getHoraInicio());
        assertEquals(LocalTime.of(11, 0), segundaPersistida.getHoraInicio());
    }

    @Test
    void duasReservasHttpSimultaneasResultamEmUmaCriacaoEUm409() throws Exception {
        LocalDate data = LocalDate.now().plusDays(15);
        List<Integer> resultados = executarSimultaneamente(
                () -> criarHttp(data, proprietario, "09:00:00", "10:00:00"),
                () -> criarHttp(data, outroCliente, "09:30:00", "10:30:00")
        );

        assertEquals(1, resultados.stream().filter(codigo -> codigo == 201).count());
        assertEquals(1, resultados.stream().filter(codigo -> codigo == 409).count());
        assertEquals(1L, contarBloqueantes(data, LocalTime.of(9, 0), LocalTime.of(10, 0),
                LocalTime.of(9, 30), LocalTime.of(10, 30)));
    }

    @Test
    void exclusionPostgresContinuaSendoAutoridadeEmDoisInsertsConcorrentes()
            throws Exception {
        LocalDate data = LocalDate.now().plusDays(16);
        CyclicBarrier barreira = new CyclicBarrier(2);

        List<Boolean> commits = executarSimultaneamente(
                () -> inserirDireto(barreira, data, LocalTime.of(9, 0), LocalTime.of(10, 0)),
                () -> inserirDireto(barreira, data, LocalTime.of(9, 30), LocalTime.of(10, 30))
        );

        assertEquals(1, commits.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, commits.stream().filter(value -> !value).count());
    }

    @Test
    void intervalosAdjacentesSaoAceitosEmConcorrenciaSemEnfraquecerExclusion()
            throws Exception {
        LocalDate data = LocalDate.now().plusDays(17);
        List<Integer> resultados = executarSimultaneamente(
                () -> criarHttp(data, proprietario, "09:00:00", "10:00:00"),
                () -> criarHttp(data, outroCliente, "10:00:00", "11:00:00")
        );

        assertEquals(List.of(201, 201), resultados.stream().sorted().toList());
    }

    @Test
    void errosDeDeadlockOuSerializacaoSaoTransitóriosENaoViraram409DeDominio()
            throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var deadlock = handler.tratarErroGenerico(
                new SQLException("deadlock", "40P01")
        );
        var serialization = handler.tratarErroGenerico(
                new SQLException("serialization failure", "40001")
        );
        assertEquals(503, deadlock.getStatusCode().value());
        assertEquals(503, serialization.getStatusCode().value());
        assertEquals("1", deadlock.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals("1", serialization.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void criarAgendamentoEmQuadraAtivaContinuaPermitido() throws Exception {
        assertEquals(
                201,
                criarHttp(
                        LocalDate.now().plusDays(18),
                        proprietario,
                        "09:00:00",
                        "10:00:00"
                )
        );
    }

    @Test
    void quadraInativaRecusaNovaReserva() throws Exception {
        assertEquals(200, alterarStatusQuadra(quadra.getId(), false));

        assertEquals(
                400,
                criarHttp(
                        LocalDate.now().plusDays(18),
                        proprietario,
                        "09:00:00",
                        "10:00:00"
                )
        );
        assertEquals(0L, contarAgendamentos(quadra.getId()));
        assertEquals(false, quadraRepository.findById(quadra.getId())
                .orElseThrow().isAtiva());
    }

    @Test
    void reagendarParaQuadraInativaRecusaESemMutacaoParcial() throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        Quadra destino = criarQuadra(false);

        assertEquals(
                400,
                atualizar(
                        agendamento.getId(),
                        LocalDate.now().plusDays(19),
                        "14:00:00",
                        "15:00:00",
                        destino.getId()
                )
        );

        Agendamento persistido = agendamentoRepository.findById(agendamento.getId())
                .orElseThrow();
        assertEquals(quadra.getId(), persistido.getQuadra().getId());
        assertEquals(agendamento.getData(), persistido.getData());
        assertEquals(LocalTime.of(9, 0), persistido.getHoraInicio());
    }

    @Test
    void reagendarParaQuadraAtivaContinuaPermitido() throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        Quadra destino = criarQuadra(true);

        assertEquals(
                200,
                atualizar(
                        agendamento.getId(),
                        LocalDate.now().plusDays(19),
                        "14:00:00",
                        "15:00:00",
                        destino.getId()
                )
        );
        assertEquals(
                destino.getId(),
                agendamentoRepository.findById(agendamento.getId())
                        .orElseThrow()
                        .getQuadra()
                        .getId()
        );
    }

    @Test
    void desativarPreservaQuadraAgendamentosEStatus() throws Exception {
        Agendamento pendente = criarAgendamento(StatusAgendamento.PENDENTE,
                LocalTime.of(9, 0));
        Agendamento confirmado = criarAgendamento(StatusAgendamento.CONFIRMADO,
                LocalTime.of(11, 0));

        assertEquals(200, alterarStatusQuadra(quadra.getId(), false));

        Quadra persistida = quadraRepository.findById(quadra.getId()).orElseThrow();
        assertEquals(false, persistida.isAtiva());
        assertEquals(StatusAgendamento.PENDENTE, statusNoBanco(pendente));
        assertEquals(StatusAgendamento.CONFIRMADO, statusNoBanco(confirmado));
        assertEquals(2L, contarAgendamentos(quadra.getId()));
    }

    @Test
    void putDeQuadraTambemDesativaSobLockESemAlterarAgendamentos() throws Exception {
        Agendamento pendente = criarAgendamento(StatusAgendamento.PENDENTE,
                LocalTime.of(9, 0));
        Agendamento confirmado = criarAgendamento(StatusAgendamento.CONFIRMADO,
                LocalTime.of(11, 0));

        int resposta = mockMvc.perform(put("/quadras/{id}", quadra.getId())
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content("""
                                {"nome":"Quadra atualizada","tipo":"BEACH_TENNIS",
                                 "ativa":false}
                                """))
                .andReturn()
                .getResponse()
                .getStatus();

        assertEquals(200, resposta);
        assertEquals(false, quadraRepository.findById(quadra.getId())
                .orElseThrow().isAtiva());
        assertEquals(StatusAgendamento.PENDENTE, statusNoBanco(pendente));
        assertEquals(StatusAgendamento.CONFIRMADO, statusNoBanco(confirmado));
        assertEquals(2L, contarAgendamentos(quadra.getId()));
    }

    @Test
    void reativarQuadraPermiteNovasReservas() throws Exception {
        assertEquals(200, alterarStatusQuadra(quadra.getId(), false));
        assertEquals(200, alterarStatusQuadra(quadra.getId(), true));
        assertEquals(
                201,
                criarHttp(
                        LocalDate.now().plusDays(20),
                        proprietario,
                        "09:00:00",
                        "10:00:00"
                )
        );
        assertEquals(1L, contarAgendamentos(quadra.getId()));
    }

    @Test
    void reservaSimultaneaComDesativacaoTerminaEmOrdemSerialValida() throws Exception {
        LocalDate data = LocalDate.now().plusDays(21);
        List<Integer> resultados = executarSimultaneamente(
                () -> criarHttp(data, proprietario, "09:00:00", "10:00:00"),
                () -> alterarStatusQuadra(quadra.getId(), false)
        );

        assertEquals(200, resultados.get(1));
        assertTrue(resultados.get(0) == 201 || resultados.get(0) == 400);
        assertEquals(false, quadraRepository.findById(quadra.getId())
                .orElseThrow().isAtiva());
        assertEquals(
                resultados.get(0) == 201 ? 1L : 0L,
                contarAgendamentos(quadra.getId())
        );
    }

    @Test
    void reservaQueEsperaLockRevalidaQuadraJaDesativada() throws Exception {
        LocalDate data = LocalDate.now().plusDays(22);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch tentativaIniciada = new CountDownLatch(1);
        AtomicReference<Future<Integer>> respostaHttp = new AtomicReference<>();

        try {
            TransactionTemplate holder = new TransactionTemplate(transactionManager);
            holder.execute(status -> {
                quadraRepository.buscarPorIdParaAtualizacao(quadra.getId())
                        .orElseThrow();
                respostaHttp.set(executor.submit(() -> {
                    tentativaIniciada.countDown();
                    return criarHttp(data, proprietario, "09:00:00", "10:00:00");
                }));
                aguardar(tentativaIniciada);
                assertTrue(aguardarLockNoPostgres(Duration.ofSeconds(10)));
                jdbcTemplate.update(
                        "UPDATE quadra SET ativa = FALSE WHERE id = ?",
                        quadra.getId()
                );
                return null;
            });

            assertEquals(400, respostaHttp.get().get(10, TimeUnit.SECONDS));
            assertEquals(0L, contarAgendamentos(quadra.getId()));
            assertEquals(false, quadraRepository.findById(quadra.getId())
                    .orElseThrow().isAtiva());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void putQueEsperaLockRevalidaQuadraDeDestinoDesativada() throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        Quadra destino = criarQuadra(true);
        LocalDate dataOriginal = agendamento.getData();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch tentativaIniciada = new CountDownLatch(1);
        AtomicReference<Future<Integer>> respostaHttp = new AtomicReference<>();

        try {
            TransactionTemplate holder = new TransactionTemplate(transactionManager);
            holder.execute(status -> {
                quadraRepository.buscarPorIdParaAtualizacao(destino.getId())
                        .orElseThrow();
                respostaHttp.set(executor.submit(() -> {
                    tentativaIniciada.countDown();
                    return atualizar(
                            agendamento.getId(),
                            LocalDate.now().plusDays(23),
                            "14:00:00",
                            "15:00:00",
                            destino.getId()
                    );
                }));
                aguardar(tentativaIniciada);
                assertTrue(aguardarLockNoPostgres(Duration.ofSeconds(10)));
                jdbcTemplate.update(
                        "UPDATE quadra SET ativa = FALSE WHERE id = ?",
                        destino.getId()
                );
                return null;
            });

            assertEquals(400, respostaHttp.get().get(10, TimeUnit.SECONDS));
            Agendamento persistido = agendamentoRepository.findById(agendamento.getId())
                    .orElseThrow();
            assertEquals(quadra.getId(), persistido.getQuadra().getId());
            assertEquals(dataOriginal, persistido.getData());
            assertEquals(LocalTime.of(9, 0), persistido.getHoraInicio());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void putSimultaneoComDesativacaoDoDestinoRespeitaOrdemDosLocks() throws Exception {
        Agendamento agendamento = criarAgendamento(LocalTime.of(9, 0));
        Quadra destino = criarQuadra(true);

        List<Integer> resultados = executarSimultaneamente(
                () -> atualizar(
                        agendamento.getId(),
                        LocalDate.now().plusDays(23),
                        "14:00:00",
                        "15:00:00",
                        destino.getId()
                ),
                () -> alterarStatusQuadra(destino.getId(), false)
        );

        int respostaPut = resultados.get(0);
        assertEquals(200, resultados.get(1));
        assertTrue(respostaPut == 200 || respostaPut == 400);
        assertEquals(false, quadraRepository.findById(destino.getId())
                .orElseThrow().isAtiva());

        Agendamento persistido = agendamentoRepository.findById(agendamento.getId())
                .orElseThrow();
        if (respostaPut == 400) {
            assertEquals(quadra.getId(), persistido.getQuadra().getId());
            assertEquals(LocalTime.of(9, 0), persistido.getHoraInicio());
        } else {
            assertEquals(destino.getId(), persistido.getQuadra().getId());
            assertEquals(LocalTime.of(14, 0), persistido.getHoraInicio());
        }
    }

    @Test
    void putEntreQuadrasConcorrenteComOutraMudancaDeDestinoNaoGeraDeadlock()
            throws Exception {
        Quadra outraQuadra = criarQuadra(true);
        LocalDate data = LocalDate.now().plusDays(24);
        Agendamento emPrimeira = criarAgendamento(StatusAgendamento.PENDENTE,
                LocalTime.of(9, 0), data, quadra);
        Agendamento emSegunda = criarAgendamento(StatusAgendamento.PENDENTE,
                LocalTime.of(11, 0), data, outraQuadra);

        List<Integer> resultados = executarSimultaneamente(
                () -> atualizar(emPrimeira.getId(), data, "11:00:00", "12:00:00",
                        outraQuadra.getId()),
                () -> atualizar(emSegunda.getId(), data, "09:00:00", "10:00:00",
                        quadra.getId())
        );

        assertEquals(List.of(409, 409), resultados);
        assertEquals(
                quadra.getId(),
                agendamentoRepository.findById(emPrimeira.getId())
                        .orElseThrow().getQuadra().getId()
        );
        assertEquals(
                outraQuadra.getId(),
                agendamentoRepository.findById(emSegunda.getId())
                        .orElseThrow().getQuadra().getId()
        );
    }

    private Agendamento criarAgendamento(LocalTime inicio) {
        return criarAgendamento(StatusAgendamento.PENDENTE, inicio);
    }

    private Agendamento criarAgendamento(StatusAgendamento status, LocalTime inicio) {
        return criarAgendamento(status, inicio, LocalDate.now().plusDays(10), quadra);
    }

    private Agendamento criarAgendamento(
            StatusAgendamento status,
            LocalTime inicio,
            LocalDate data,
            Quadra quadraDoAgendamento) {
        Agendamento agendamento = new Agendamento();
        agendamento.setData(data);
        agendamento.setHoraInicio(inicio);
        agendamento.setHoraFim(inicio.plusHours(1));
        agendamento.setStatus(status);
        agendamento.setUsuario(proprietario);
        agendamento.setQuadra(quadraDoAgendamento);
        return agendamentoRepository.saveAndFlush(agendamento);
    }

    private Quadra criarQuadra(boolean ativa) {
        Quadra nova = new Quadra();
        nova.setNome("Quadra teste " + UUID.randomUUID());
        nova.setTipo(TipoQuadra.BEACH_TENNIS);
        nova.setAtiva(ativa);
        return quadraRepository.saveAndFlush(nova);
    }

    private Usuario criarUsuario(Perfil perfil) {
        Usuario usuario = new Usuario();
        usuario.setNome("Usuário concorrente " + UUID.randomUUID());
        usuario.setEmail(UUID.randomUUID() + "@example.invalid");
        usuario.setTelefone("11999999999");
        usuario.setSenha("hash-de-teste");
        usuario.setPerfil(perfil);
        return usuarioRepository.saveAndFlush(usuario);
    }

    private void definirIdadeEmMinutos(Agendamento agendamento, int minutos) {
        jdbcTemplate.update(
                """
                UPDATE agendamento
                SET criado_em = clock_timestamp() - (? * INTERVAL '1 minute')
                WHERE id = ?
                """,
                minutos,
                agendamento.getId()
        );
    }

    private int confirmar(Long id) throws Exception {
        return mockMvc.perform(patch("/agendamentos/{id}/confirmacao", id)
                        .header("Authorization", bearer(admin)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int cancelar(Long id, Usuario ator) throws Exception {
        return mockMvc.perform(patch("/agendamentos/{id}/cancelamento", id)
                        .header("Authorization", bearer(ator)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int atualizar(Long id, LocalDate data, String inicio, String fim)
            throws Exception {
        return atualizar(id, data, inicio, fim, quadra.getId());
    }

    private int atualizar(
            Long id,
            LocalDate data,
            String inicio,
            String fim,
            Long quadraId) throws Exception {
        String corpo = """
                {"data":"%s","horaInicio":"%s","horaFim":"%s","quadraId":%d}
                """.formatted(data, inicio, fim, quadraId);
        return mockMvc.perform(put("/agendamentos/{id}", id)
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content(corpo))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int reatribuir(Long id, Usuario novoProprietario) throws Exception {
        return mockMvc.perform(patch("/agendamentos/{id}/proprietario", id)
                        .header("Authorization", bearer(admin))
                        .contentType("application/json")
                        .content("{\"usuarioId\":" + novoProprietario.getId() + "}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int criarHttp(
            LocalDate data,
            Usuario usuario,
            String inicio,
            String fim) throws Exception {
        return criarHttp(data, usuario, inicio, fim, quadra.getId());
    }

    private int criarHttp(
            LocalDate data,
            Usuario usuario,
            String inicio,
            String fim,
            Long quadraId) throws Exception {
        String corpo = """
                {"data":"%s","horaInicio":"%s","horaFim":"%s",
                 "usuarioId":%d,"quadraId":%d}
                """.formatted(data, inicio, fim, usuario.getId(), quadraId);
        return mockMvc.perform(post("/agendamentos")
                        .header("Authorization", bearer(usuario))
                        .contentType("application/json")
                        .content(corpo))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int alterarStatusQuadra(Long quadraId, boolean ativa) throws Exception {
        return mockMvc.perform(patch("/quadras/{id}/status", quadraId)
                        .header("Authorization", bearer(admin))
                        .param("ativa", Boolean.toString(ativa)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private boolean inserirDireto(
            CyclicBarrier barreira,
            LocalDate data,
            LocalTime inicio,
            LocalTime fim) throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.execute(status -> {
                try {
                    barreira.await(10, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                jdbcTemplate.update(
                        """
                        INSERT INTO agendamento
                            (data, hora_inicio, hora_fim, status, usuario_id, quadra_id)
                        VALUES (?, ?, ?, 'PENDENTE', ?, ?)
                        """,
                        data,
                        inicio,
                        fim,
                        proprietario.getId(),
                        quadra.getId()
                );
                return null;
            });
            return true;
        } catch (DataIntegrityViolationException ex) {
            if (sqlState(ex, "23P01")) {
                return false;
            }
            throw ex;
        } catch (PessimisticLockingFailureException ex) {
            // A disputa concorrente na EXCLUDE também pode abortar com deadlock.
            if (sqlState(ex, "40P01")) {
                return false;
            }
            throw ex;
        }
    }

    private long contarBloqueantes(
            LocalDate data,
            LocalTime inicioA,
            LocalTime fimA,
            LocalTime inicioB,
            LocalTime fimB) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM agendamento
                WHERE quadra_id = ?
                  AND data = ?
                  AND status IN ('PENDENTE', 'CONFIRMADO')
                  AND ((hora_inicio = ? AND hora_fim = ?)
                    OR (hora_inicio = ? AND hora_fim = ?))
                """,
                Long.class,
                quadra.getId(),
                data,
                inicioA,
                fimA,
                inicioB,
                fimB
        );
    }

    private StatusAgendamento statusNoBanco(Agendamento agendamento) {
        return agendamentoRepository.findById(agendamento.getId())
                .orElseThrow()
                .getStatus();
    }

    private long contarAgendamentos(Long quadraId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM agendamento WHERE quadra_id = ?",
                Long.class,
                quadraId
        );
    }

    private String bearer(Usuario usuario) {
        return "Bearer " + jwtService.generateToken(usuario);
    }

    private <T> List<T> executarSimultaneamente(
            Callable<T> primeira,
            Callable<T> segunda) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barreira = new CyclicBarrier(2);
        try {
            Future<T> uma = executor.submit(() -> {
                barreira.await(10, TimeUnit.SECONDS);
                return primeira.call();
            });
            Future<T> duas = executor.submit(() -> {
                barreira.await(10, TimeUnit.SECONDS);
                return segunda.call();
            });
            return List.of(
                    uma.get(20, TimeUnit.SECONDS),
                    duas.get(20, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void aguardar(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private boolean aguardarLockNoPostgres(Duration limite) {
        long deadline = System.nanoTime() + limite.toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("""
                         SELECT count(*)
                         FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND pid <> pg_backend_pid()
                           AND wait_event_type = 'Lock'
                         """)) {
                if (resultSet.next() && resultSet.getLong(1) > 0) {
                    return true;
                }
            } catch (SQLException ex) {
                throw new AssertionError("Falha ao observar a espera de lock.", ex);
            }
            Thread.yield();
        }
        return false;
    }

    private boolean sqlState(Throwable erro, String esperado) {
        for (Throwable causa = erro; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql
                    && esperado.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

}
