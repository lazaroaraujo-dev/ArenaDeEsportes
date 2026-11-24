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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
                extra.setPerfil(Perfil.CLIENTE);
                usuarioRepository.saveAndFlush(extra);
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
        Usuario u = new Usuario();
        u.setNome("Usuario " + prefixo + " " + sufixo);
        u.setEmail(prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid");
        u.setTelefone("11988887777");
        u.setSenha(passwordEncoder.encode("senha123!"));
        u.setPerfil(perfil);
        return usuarioRepository.saveAndFlush(u);
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
     * Teste 4b: LastAdminException ATRAVESSA o limite transacional externo.
     * O noRollbackFor de deletar(Long) atua somente na participação interna. Ao propagar,
     * a política padrão do TransactionTemplate externo prevalece e reverte a auditoria.
     */
    @Test
    void lastAdminExceptionAtravessaLimiteTransacionalExternoEProvocaRollbackDaAuditoria() {
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
                                "O noRollbackFor interno não marca a transação participante como rollback-only");
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
        assertEquals(contadorAntes, depois != null ? depois : 0L,
                "Ao propagar pelo limite externo, a regra padrão de rollback reverte também a auditoria");
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
     * Teste LAC-05 (complementação): rollback técnico real em alterarPerfil().
     * <p>
     * Invoca UsuarioService#alterarPerfil e injeta uma falha técnica controlada
     * por spy logo após o saveAndFlush() e o registro real da auditoria
     * PROMOTE/SUCCEEDED, ainda antes do commit. Nenhuma lógica de falha é
     * adicionada ao código de produção.
     * Verifica, por conexão independente, que perfil, auditoria e estado
     * administrativo foram revertidos e que o advisory lock foi liberado.
     */
    @Test
    void rollbackTecnicoPreservaPerfisEAuditoriaConsistente() {
        Usuario adminOperador = obterPrimeiroAdmin();
        Usuario clienteAlvo = criarUsuario("cliente_rollback", Perfil.CLIENTE);
        autenticarComo(adminOperador);

        Long auditoriaAntes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND operation = 'PROMOTE' AND outcome = 'SUCCEEDED'",
                Long.class,
                clienteAlvo.getId()
        );
        long contAuditoriaAntes = auditoriaAntes != null ? auditoriaAntes : 0L;

        RuntimeException falhaSimulada = new RuntimeException("Falha técnica inesperada simulada (LAC-05)");

        doAnswer(invocation -> {
            // saveAndFlush() já ocorreu: a mutação deve estar visível na transação.
            String perfilDuranteTransacao = jdbcTemplate.queryForObject(
                    "SELECT perfil FROM public.usuario WHERE id = ?",
                    String.class,
                    clienteAlvo.getId()
            );
            assertEquals("ADMIN", perfilDuranteTransacao,
                    "A mutação de perfil deve ocorrer antes da falha, dentro da transação");

            // Executa o registro real de auditoria e só então provoca a falha técnica.
            // arena_runtime não tem SELECT em admin_audit_event (DB-02); o retorno
            // não nulo do SECURITY DEFINER comprova a inserção na transação.
            Object idEvento = invocation.callRealMethod();
            assertNotNull(idEvento, "O evento de auditoria deve ser inserido antes da falha");

            throw falhaSimulada;
        }).when(adminLifecycleDbServiceSpy).registrarAuditoria(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
        );

        RuntimeException lancada = assertThrows(RuntimeException.class,
                () -> usuarioService.alterarPerfil(
                        clienteAlvo.getId(),
                        UsuarioPerfilDTO.builder().perfil(Perfil.ADMIN).build()
                ),
                "A falha técnica injetada deve interromper alterarPerfil() antes do commit"
        );
        assertEquals("Falha técnica inesperada simulada (LAC-05)", lancada.getMessage());

        verify(adminLifecycleDbServiceSpy).registrarAuditoria(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()
        );

        String perfilPersistido = migratorJdbc.queryForObject(
                "SELECT perfil FROM public.usuario WHERE id = ?",
                String.class,
                clienteAlvo.getId()
        );
        assertEquals("CLIENTE", perfilPersistido,
                "O rollback deve preservar o perfil original");
        assertNotEquals("ADMIN", perfilPersistido,
                "A mutação para ADMIN não pode sobreviver ao rollback");

        Long auditoriaDepois = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event WHERE target_user_id = ? AND operation = 'PROMOTE' AND outcome = 'SUCCEEDED'",
                Long.class,
                clienteAlvo.getId()
        );
        assertEquals(contAuditoriaAntes, auditoriaDepois != null ? auditoriaDepois : 0L,
                "O evento PROMOTE/SUCCEEDED inserido na transação revertida não pode ser persistido");

        Map<String, Object> estado = migratorJdbc.queryForMap(
                "SELECT p_admin_count, p_bootstrap_consumed, p_recovery_required FROM public.h04_get_admin_lifecycle_state()"
        );
        Long adminCountReal = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.usuario WHERE perfil = 'ADMIN'",
                Long.class
        );
        assertEquals(((Number) estado.get("p_admin_count")).longValue(), adminCountReal != null ? adminCountReal : 0L,
                "O estado administrativo deve permanecer consistente após o rollback");
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
                "O advisory lock não pode permanecer retido após o rollback");
    }

    /**
     * Teste 7: Comprova que o advisory lock 7409395520021 permanece ativo durante a operação do método legado.
     */
    @Test
    void advisoryLockPermaneceAtivoDuranteOperacaoDoMetodoLegado() throws Exception {
        Usuario adminOperador = obterPrimeiroAdmin();
        Usuario adminAlvo = criarUsuario("admin_lock_test", Perfil.ADMIN);

        ExecutorService executorA = Executors.newSingleThreadExecutor();
        CountDownLatch lockAcquiredByALatch = new CountDownLatch(1);
        CountDownLatch bPidCapturedLatch = new CountDownLatch(1);
        AtomicLong pidA = new AtomicLong();
        AtomicLong pidB = new AtomicLong();

        // Thread A adquire o lock primeiro em transação manual e registra seu PID
        Future<?> futureA = executorA.submit(() -> {
            org.springframework.transaction.support.DefaultTransactionDefinition def =
                    new org.springframework.transaction.support.DefaultTransactionDefinition();
            def.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            org.springframework.transaction.TransactionStatus status = transactionManager.getTransaction(def);

            try {
                jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY + ")");
                Integer pidAValue = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                assertNotNull(pidAValue, "PID da sessão de A deve estar disponível");
                pidA.set(pidAValue.longValue());

                Long locksA = jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM pg_locks
                        WHERE pid = ?
                          AND locktype = 'advisory'
                          AND ((classid::bigint << 32) | objid::bigint) = ?
                          AND objsubid = 1
                          AND granted = true
                        """,
                        Long.class,
                        pidA.get(),
                        AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY
                );
                assertEquals(1L, locksA != null ? locksA : 0L,
                        "A sessão de A deve ser a detentora do advisory lock");

                lockAcquiredByALatch.countDown();

                assertTrue(bPidCapturedLatch.await(5, TimeUnit.SECONDS), "Thread B deve registrar seu PID");

                // Comprova que a sessão específica de Thread B está bloqueada no advisory lock
                boolean bBloqueado = false;
                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline) {
                    Integer waiting = jdbcTemplate.queryForObject(
                            """
                            SELECT count(*) FROM pg_locks
                            WHERE pid = ?
                              AND locktype = 'advisory'
                              AND ((classid::bigint << 32) | objid::bigint) = ?
                              AND objsubid = 1
                              AND NOT granted
                            """,
                            Integer.class,
                            pidB.get(),
                            AdminLifecycleDbService.ADMIN_LIFECYCLE_LOCK_KEY
                    );
                    if (waiting != null && waiting > 0) {
                        bBloqueado = true;
                        break;
                    }
                    Thread.sleep(50);
                }
                assertTrue(bBloqueado,
                        "A sessão " + pidB.get() + " de B deve estar bloqueada no advisory lock 7409395520021");

                // Libera o lock commitando
                transactionManager.commit(status);
            } catch (Throwable ex) {
                if (!status.isCompleted()) {
                    transactionManager.rollback(status);
                }
                throw new RuntimeException(ex);
            }
        });

        assertTrue(lockAcquiredByALatch.await(5, TimeUnit.SECONDS), "Thread A deve adquirir o lock");

        // Captura o PID dentro do adquirirLock() do próprio serviço, sem transação externa.
        doAnswer(invocation -> {
            Integer pidBValue = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertNotNull(pidBValue, "PID da sessão de B deve estar disponível");
            pidB.set(pidBValue.longValue());
            bPidCapturedLatch.countDown();
            return invocation.callRealMethod();
        }).when(adminLifecycleDbServiceSpy).adquirirLock();

        // Thread B: chama o método legado usuarioService.deletar
        ExecutorService executorB = Executors.newSingleThreadExecutor();
        Future<?> futureB = executorB.submit(() -> {
            autenticarComo(adminOperador);
            usuarioService.deletar(adminAlvo.getId());
        });

        futureA.get(15, TimeUnit.SECONDS);
        futureB.get(15, TimeUnit.SECONDS);

        executorA.shutdown();
        executorB.shutdown();

        assertFalse(usuarioRepository.existsById(adminAlvo.getId()), "O administrador alvo deve ser excluído após Thread B adquirir o lock");
    }
}
