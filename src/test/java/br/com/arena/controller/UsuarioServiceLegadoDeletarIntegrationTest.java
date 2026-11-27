package br.com.arena.controller;

import br.com.arena.dto.usuario.UsuarioPerfilDTO;
import br.com.arena.enumerate.Perfil;
import br.com.arena.enumerate.StatusAgendamento;
import br.com.arena.enumerate.TipoQuadra;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.LastAdminException;
import br.com.arena.exception.ResourceNotFoundException;
import br.com.arena.model.Agendamento;
import br.com.arena.model.Quadra;
import br.com.arena.model.Usuario;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.repository.QuadraRepository;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.service.AdminLifecycleDbService;
import br.com.arena.service.AdminWriterJdbcService;
import br.com.arena.service.UsuarioService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@IsolatedPostgresIntegrationTest
class UsuarioServiceLegadoDeletarIntegrationTest {

    private static final String EXPECTED_TEST_DATABASE = "arena_flyway_test";

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private QuadraRepository quadraRepository;

    @Autowired
    private AgendamentoRepository agendamentoRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcTemplate migratorJdbc;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AdminLifecycleDbService adminLifecycleDbServiceSpy;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AdminWriterJdbcService adminWriterJdbcServiceSpy;

    @BeforeEach
    void setup() {
        migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        garantirEstadoAtivoComAdmin();
        limparAdminsSobressalentes();
    }

    @AfterEach
    void teardown() {
        SecurityContextHolder.clearContext();
    }

    private void limparAdminsSobressalentes() {
        List<Usuario> admins = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .toList();

        if (admins.size() > 1) {
            for (int i = 1; i < admins.size(); i++) {
                Usuario extra = admins.get(i);
                migratorJdbc.update(
                        "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE id = ?",
                        extra.getId()
                );
            }
        }
    }

    private synchronized void garantirEstadoAtivoComAdmin() {
        Map<String, Object> state = migratorJdbc.queryForMap(
                "SELECT bootstrap_consumed, recovery_required, admin_count FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        boolean recoveryRequired = Boolean.TRUE.equals(state.get("recovery_required"));

        if (recoveryRequired) {
            TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
            DriverManagerDataSource offlineDs = new DriverManagerDataSource();
            offlineDs.setUrl(settings.url());
            offlineDs.setUsername(settings.offlineUsername());
            offlineDs.setPassword(settings.offlinePassword());
            JdbcTemplate offlineJdbc = new JdbcTemplate(offlineDs);

            String senhaHash = passwordEncoder.encode("adminMaster123!");
            offlineJdbc.queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Master', 'admin_master@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup inicial para testes', 'TICKET-TEST'
                    )
                    """,
                    Long.class,
                    senhaHash
            );
        }
    }

    private Usuario obterPrimeiroAdmin() {
        return usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhum ADMIN encontrado no banco de testes."));
    }

    private Usuario criarUsuario(String prefixo, Perfil perfil) {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        Long id = migratorJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES (?, ?, ?, ?, ?::varchar)
                RETURNING id
                """,
                Long.class,
                "Usuario " + prefixo + " " + sufixo,
                prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid",
                "11988887777",
                passwordEncoder.encode("senha123!"),
                perfil.name()
        );

        return usuarioRepository.findById(id).orElseThrow();
    }

    private void autenticarComo(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities())
        );
    }

    /**
     * Teste 1: O comportamento do método legado quando a exclusão é válida.
     */
    @Test
    void metodoLegadoExcluiAdminComSucessoQuandoExistemOutrosAdmins() {
        Usuario adminOperador = obterPrimeiroAdmin();
        Usuario adminAlvo = criarUsuario("admin_alvo", Perfil.ADMIN);
        autenticarComo(adminOperador);

        // Invoca o método legado
        usuarioService.deletar(adminAlvo.getId());

        assertFalse(usuarioRepository.existsById(adminAlvo.getId()), "O administrador alvo deve ser excluído do banco");

        Long auditoriasSucesso = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND operation = 'ADMIN_DELETE' AND outcome = 'SUCCEEDED'",
                Long.class,
                adminAlvo.getId()
        );
        assertEquals(1L, auditoriasSucesso != null ? auditoriasSucesso : 0L, "Deve registrar auditoria SUCCEEDED");
    }

    /**
     * Teste 1.b: Método legado lança ResourceNotFoundException para usuário inexistente.
     */
    @Test
    void metodoLegadoLancaResourceNotFoundExceptionQuandoNaoExiste() {
        Usuario adminOperador = obterPrimeiroAdmin();
        autenticarComo(adminOperador);

        assertThrows(ResourceNotFoundException.class, () -> usuarioService.deletar(99999999L));
    }

    /**
     * Teste 2: A rejeição da exclusão do último ADMIN.
     */
    @Test
    void rejeicaoExclusaoUltimoAdminLancaLastAdminExceptionENaoDeleta() {
        Usuario ultimoAdmin = obterPrimeiroAdmin();
        autenticarComo(ultimoAdmin);

        LastAdminException ex = assertThrows(LastAdminException.class, () -> usuarioService.deletar(ultimoAdmin.getId()));
        assertTrue(ex instanceof BusinessException, "LastAdminException deve estender BusinessException para compatibilidade");
        assertTrue(usuarioRepository.existsById(ultimoAdmin.getId()), "O último administrador NÃO pode ser excluído");
    }

    /**
     * Teste 3 e 5: Persistência do evento LAST_ADMIN quando NÃO existe transação externa.
     */
    @Test
    void persistenciaEventoLastAdminAposRejeicaoSemTransacaoExterna() {
        Usuario ultimoAdmin = obterPrimeiroAdmin();
        autenticarComo(ultimoAdmin);

        Long antes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND error_code = 'LAST_ADMIN'",
                Long.class,
                ultimoAdmin.getId()
        );
        long contadorAntes = antes != null ? antes : 0L;

        assertThrows(LastAdminException.class, () -> usuarioService.deletar(ultimoAdmin.getId()));

        Long depois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND error_code = 'LAST_ADMIN' AND outcome = 'REJECTED'",
                Long.class,
                ultimoAdmin.getId()
        );
        assertEquals(contadorAntes + 1L, depois != null ? depois : 0L,
                "A auditoria LAST_ADMIN deve ser persistida mesmo após o lançamento de LastAdminException sem transação externa");
    }

    /**
     * Teste 4a: LastAdminException é capturada pelo próprio chamador externo.
     * Nesse cenário a exceção NÃO atravessa o limite transacional externo; por isso
     * o noRollbackFor interno evita o rollback-only e a auditoria é comitada.
     */
    @Test
    void lastAdminExceptionCapturadaPeloChamadorExternoComitaAuditoria() {
        Usuario ultimoAdmin = obterPrimeiroAdmin();
        autenticarComo(ultimoAdmin);

        Long antes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND error_code = 'LAST_ADMIN'",
                Long.class,
                ultimoAdmin.getId()
        );
        long contadorAntes = antes != null ? antes : 0L;

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.execute(status -> {
            assertThrows(LastAdminException.class, () -> usuarioService.deletar(ultimoAdmin.getId()));
            assertFalse(status.isRollbackOnly(),
                    "Com a exceção capturada internamente, o limite externo não é marcado como rollback-only");
            return null;
        });

        Long depois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND error_code = 'LAST_ADMIN' AND outcome = 'REJECTED'",
                Long.class,
                ultimoAdmin.getId()
        );
        assertEquals(contadorAntes + 1L, depois != null ? depois : 0L,
                "Sem propagação para o limite externo, a auditoria LAST_ADMIN é comitada");
    }

    /**
     * Teste 4b (Etapa 3): LastAdminException ATRAVESSA o limite transacional externo,
     * mas a auditoria da função permanece commitada porque o writer usa
     * PROPAGATION_REQUIRES_NEW em datasource independente.
     */
    @Test
    void lastAdminExceptionAtravessaLimiteTransacionalExternoEAuditoriaPermaneceCommitada() {
        Usuario ultimoAdmin = obterPrimeiroAdmin();
        autenticarComo(ultimoAdmin);

        Long antes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND error_code = 'LAST_ADMIN'",
                Long.class,
                ultimoAdmin.getId()
        );
        long contadorAntes = antes != null ? antes : 0L;

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        LastAdminException lancada = assertThrows(LastAdminException.class, () ->
                txTemplate.execute(status -> {
                    try {
                        usuarioService.deletar(ultimoAdmin.getId());
                    } catch (LastAdminException ex) {
                        assertFalse(status.isRollbackOnly(),
                                "A transação externa não é marcada como rollback-only pelo serviço");
                        throw ex;
                    }
                    return null;
                })
        );
        assertNotNull(lancada.getMessage());

        Long depois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND error_code = 'LAST_ADMIN' AND outcome = 'REJECTED'",
                Long.class,
                ultimoAdmin.getId()
        );
        assertEquals(contadorAntes + 1L, depois != null ? depois : 0L,
                "A auditoria LAST_ADMIN foi commitada pelo writer antes da exceção HTTP");
        assertTrue(usuarioRepository.existsById(ultimoAdmin.getId()),
                "O último ADMIN permanece persistido após o rollback externo");
    }

    /**
     * Teste 6: Rollback em caso de erro técnico ou regra de negócio com BusinessException padrão.
     * Comprova que BusinessException genérica (ex: agendamentos vinculados) continua disparando rollback.
     */
    @Test
    void rollbackOcorreEmExclusaoDeUsuarioComAgendamentos() {
        Usuario adminOperador = obterPrimeiroAdmin();
        Usuario cliente = criarUsuario("cliente_agendado", Perfil.CLIENTE);
        autenticarComo(adminOperador);

        Quadra quadra = new Quadra();
        quadra.setNome("Quadra Teste " + UUID.randomUUID().toString().substring(0, 6));
        quadra.setTipo(TipoQuadra.FUTVOLEI);
        quadra.setAtiva(true);
        quadraRepository.saveAndFlush(quadra);

        Agendamento ag = new Agendamento();
        ag.setUsuario(cliente);
        ag.setQuadra(quadra);
        ag.setData(LocalDate.now().plusDays(2));
        ag.setHoraInicio(LocalTime.of(14, 0));
        ag.setHoraFim(LocalTime.of(15, 0));
        ag.setStatus(StatusAgendamento.CONFIRMADO);
        agendamentoRepository.saveAndFlush(ag);

        // Deve lançar BusinessException padrão (que NÃO está no noRollbackFor)
        BusinessException ex = assertThrows(BusinessException.class, () -> usuarioService.deletar(cliente.getId()));
        assertFalse(ex instanceof LastAdminException, "Deve ser uma BusinessException genérica, não LastAdminException");
        assertTrue(usuarioRepository.existsById(cliente.getId()), "O cliente deve permanecer persistido após rollback");
    }

    /**
     * Teste LAC-05 (adaptado à V4.3): uma falha técnica antes da chamada da função
     * do writer não pode deixar mutação, auditoria, estado inconsistente ou lock retido.
     * A mutação e a auditoria agora são atômicas dentro da função SECURITY DEFINER;
     * não há mais ponto na aplicação para injetar falha entre elas.
     */
    @Test
    void rollbackTecnicoPreservaPerfisEAuditoriaConsistente() {
        Usuario adminOperador = obterPrimeiroAdmin();
        Usuario clienteAlvo = criarUsuario("cliente_rollback", Perfil.CLIENTE);
        autenticarComo(adminOperador);

        long contAuditoriaAntes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND operation = 'PROMOTE' AND outcome = 'SUCCEEDED'",
                Long.class,
                clienteAlvo.getId()
        );

        RuntimeException falhaSimulada = new RuntimeException("Falha técnica inesperada simulada (LAC-05)");
        doThrow(falhaSimulada).when(adminWriterJdbcServiceSpy).alterarPerfil(
                anyLong(), anyLong(), any(), any(), any(), any(), any()
        );

        RuntimeException lancada = assertThrows(RuntimeException.class,
                () -> usuarioService.alterarPerfil(
                        clienteAlvo.getId(),
                        UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build()
                ),
                "A falha técnica injetada deve interromper alterarPerfil()"
        );
        assertEquals("Falha técnica inesperada simulada (LAC-05)", lancada.getMessage());

        verify(adminWriterJdbcServiceSpy).alterarPerfil(
                anyLong(), anyLong(), any(), any(), any(), any(), any()
        );

        String perfilPersistido = migratorJdbc.queryForObject(
                "SELECT perfil FROM public.usuario WHERE id = ?",
                String.class,
                clienteAlvo.getId()
        );
        assertEquals("CLIENTE", perfilPersistido,
                "A falha técnica não pode promover o usuário");

        Long auditoriaDepois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND operation = 'PROMOTE' AND outcome = 'SUCCEEDED'",
                Long.class,
                clienteAlvo.getId()
        );
        assertEquals(contAuditoriaAntes, auditoriaDepois != null ? auditoriaDepois : 0L,
                "Nenhum evento PROMOTE/SUCCEEDED pode ser persistido");

        Map<String, Object> estado = migratorJdbc.queryForMap(
                "SELECT p_admin_count, p_bootstrap_consumed, p_recovery_required FROM public.h04_get_admin_lifecycle_state()"
        );
        Long adminCountReal = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.usuario WHERE perfil = 'ADMIN'",
                Long.class
        );
        assertEquals(((Number) estado.get("p_admin_count")).longValue(), adminCountReal != null ? adminCountReal : 0L,
                "O estado administrativo deve permanecer consistente após a falha");
        assertEquals(Boolean.TRUE, estado.get("p_bootstrap_consumed"));
        assertEquals(Boolean.FALSE, estado.get("p_recovery_required"));

        Long locksRetidos = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM pg_locks
                WHERE locktype = 'advisory'
                  AND objsubid = 1
                  AND (classid::bigint << 32) | objid::bigint = ?
                  AND granted = true
                """,
                Long.class,
                AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY
        );
        assertEquals(0L, locksRetidos != null ? locksRetidos : 0L,
                "O advisory lock não pode permanecer retido após a falha");
    }

    /**
     * Teste 7 (Etapa 3): o método legado delega à função do writer, que adquire o
     * mesmo advisory lock; a exclusão aguarda até que a outra sessão o libere.
     */
    @Test
    void operacaoDoMetodoLegadoAguardaAdvisoryLockDaFuncao() throws Exception {
        Usuario adminOperador = obterPrimeiroAdmin();
        Usuario adminAlvo = criarUsuario("admin_lock_test", Perfil.ADMIN);

        ExecutorService executorA = Executors.newSingleThreadExecutor();
        ExecutorService executorB = Executors.newSingleThreadExecutor();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch liberarLock = new CountDownLatch(1);
        CountDownLatch exclusaoConcluida = new CountDownLatch(1);
        AtomicReference<Throwable> falhaExclusao = new AtomicReference<>();

        Future<?> futureA = executorA.submit(() -> {
            org.springframework.transaction.support.DefaultTransactionDefinition def =
                    new org.springframework.transaction.support.DefaultTransactionDefinition();
            def.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            org.springframework.transaction.TransactionStatus status = transactionManager.getTransaction(def);

            try {
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock("
                        + AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY + ")");
                lockAcquired.countDown();
                assertTrue(liberarLock.await(10, TimeUnit.SECONDS), "O teste deve liberar o lock");
                transactionManager.commit(status);
            } catch (Throwable ex) {
                if (!status.isCompleted()) {
                    transactionManager.rollback(status);
                }
                throw new RuntimeException(ex);
            }
        });

        try {
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "Thread A deve adquirir o lock");

            Future<?> futureB = executorB.submit(() -> {
                try {
                    autenticarComo(adminOperador);
                    usuarioService.deletar(adminAlvo.getId());
                } catch (Throwable ex) {
                    falhaExclusao.set(ex);
                } finally {
                    exclusaoConcluida.countDown();
                }
            });

            assertFalse(exclusaoConcluida.await(500, TimeUnit.MILLISECONDS),
                    "A operação deve aguardar o advisory lock detido por outra sessão");

            liberarLock.countDown();

            assertTrue(exclusaoConcluida.await(10, TimeUnit.SECONDS),
                    "A operação deve concluir após a liberação do lock");
            futureA.get(15, TimeUnit.SECONDS);
            futureB.get(15, TimeUnit.SECONDS);
            assertNull(falhaExclusao.get());
        } finally {
            liberarLock.countDown();
            executorA.shutdownNow();
            executorB.shutdownNow();
        }

        assertFalse(usuarioRepository.existsById(adminAlvo.getId()),
                "O administrador alvo deve ser excluído após a liberação do lock");
    }
}
