package br.com.arena.controller;

import br.com.arena.enumerate.Perfil;
import br.com.arena.model.Usuario;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@IsolatedPostgresIntegrationTest
class UsuarioClienteExclusaoConsistenciaIntegrationTest {

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean(reset = MockReset.AFTER)
    private AdminLifecycleDbService adminLifecycleDbServiceSpy;

    private JdbcTemplate migratorJdbc;

    @BeforeEach
    void setup() {
        migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        garantirEstadoAtivoComAdmin();
        limparAdminsSobressalentes();
    }

    @AfterEach
    void teardown() {
        SecurityContextHolder.clearContext();
        restaurarEstadoConsistente();
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

    private void restaurarEstadoConsistente() {
        long real = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .count();
        migratorJdbc.update(
                "UPDATE public.admin_lifecycle_state SET admin_count = ?, bootstrap_consumed = true, bootstrap_consumed_at = COALESCE(bootstrap_consumed_at, now()), recovery_required = false WHERE singleton_id = 1",
                real
        );
    }

    private Usuario obterPrimeiroAdmin() {
        return usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhum ADMIN encontrado no banco de testes."));
    }

    private Usuario criarCliente(String prefixo) {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String email = prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid";
        Long id = migratorJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES (?, ?, ?, ?, 'CLIENTE')
                RETURNING id
                """,
                Long.class,
                "Cliente " + prefixo + " " + sufixo,
                email,
                "11988887777",
                passwordEncoder.encode("senha123!")
        );

        return usuarioRepository.findById(id).orElseThrow();
    }

    private void autenticarComo(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities())
        );
    }

    /**
     * 1. Exclusão normal de CLIENTE quando o estado administrativo está consistente.
     */
    @Test
    void exclusaoNormalDeClienteQuandoEstadoConsistenteComSucesso() {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarCliente("normal");
        autenticarComo(admin);

        var result = usuarioService.deletarAdministrativo(cliente.getId());

        assertEquals(br.com.arena.dto.usuario.AdminOperationStatus.SUCCESS, result.status());
        assertFalse(usuarioRepository.existsById(cliente.getId()), "O cliente deve ser excluído com sucesso");
    }

    /**
     * 2. Exclusão de CLIENTE rejeitada preventivamente quando admin_count diverge da quantidade real de administradores.
     */
    @Test
    void exclusaoDeClienteRejeitadaQuandoAdminCountDiverge() {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarCliente("divergente");
        autenticarComo(admin);

        // Força divergência no singleton administrativo
        migratorJdbc.update("UPDATE public.admin_lifecycle_state SET admin_count = 999 WHERE singleton_id = 1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            usuarioService.deletarAdministrativo(cliente.getId());
        });

        assertTrue(ex.getMessage().contains("estado administrativo inconsistente"),
                "A função deve falhar fechado com erro interno (SQLSTATE 55000)");
        assertTrue(ex.getCause() != null
                        && ex.getCause().getMessage() != null
                        && ex.getCause().getMessage().contains("admin_count"),
                "A causa original da função deve ser preservada, sem mascaramento");
        assertTrue(usuarioRepository.existsById(cliente.getId()),
                "Cliente NÃO deve ser excluído quando validação preventiva falha");
    }

    /**
     * 3. Exclusão de CLIENTE rejeitada quando recovery_required = true (sistema em estado de recuperação).
     */
    @Test
    void exclusaoDeClienteRejeitadaQuandoRecoveryRequired() {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarCliente("recovery");
        autenticarComo(admin);

        // Força recovery_required = true
        migratorJdbc.update("UPDATE public.admin_lifecycle_state SET recovery_required = true WHERE singleton_id = 1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            usuarioService.deletarAdministrativo(cliente.getId());
        });

        assertTrue(ex.getMessage().contains("estado administrativo inconsistente"),
                "A função deve falhar fechado com erro interno (SQLSTATE 55000)");
        assertTrue(ex.getCause() != null
                        && ex.getCause().getMessage() != null
                        && ex.getCause().getMessage().contains("recovery_required"),
                "A causa original da função deve ser preservada");
        assertTrue(usuarioRepository.existsById(cliente.getId()),
                "Cliente NÃO deve ser excluído em estado de recovery");
    }

    /**
     * 3.b. Exclusão de CLIENTE rejeitada quando bootstrap_consumed = false.
     */
    @Test
    void exclusaoDeClienteRejeitadaQuandoBootstrapNaoConsumido() {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarCliente("pending");
        autenticarComo(admin);

        // Força bootstrap_consumed = false coerente com ck_admin_lifecycle_state_bootstrap_coherent
        migratorJdbc.update("UPDATE public.admin_lifecycle_state SET bootstrap_consumed = false, bootstrap_consumed_at = NULL WHERE singleton_id = 1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            usuarioService.deletarAdministrativo(cliente.getId());
        });

        assertTrue(ex.getMessage().contains("estado administrativo inconsistente"),
                "A função deve falhar fechado com erro interno (SQLSTATE 55000)");
        assertTrue(ex.getCause() != null
                        && ex.getCause().getMessage() != null
                        && ex.getCause().getMessage().contains("bootstrap_consumed"),
                "A causa original da função deve ser preservada");
        assertTrue(usuarioRepository.existsById(cliente.getId()),
                "Cliente NÃO deve ser excluído antes do consumo do bootstrap");
    }

    /**
     * 4. Ausência de exclusão parcial quando a validação falha.
     */
    @Test
    void ausenciaDeExclusaoParcialQuandoValidacaoFalha() {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente1 = criarCliente("cliente1");
        Usuario cliente2 = criarCliente("cliente2");
        autenticarComo(admin);

        // Força estado inconsistente
        migratorJdbc.update("UPDATE public.admin_lifecycle_state SET admin_count = 500 WHERE singleton_id = 1");

        assertThrows(IllegalStateException.class, () -> usuarioService.deletarAdministrativo(cliente1.getId()));
        assertThrows(IllegalStateException.class, () -> usuarioService.deletarAdministrativo(cliente2.getId()));

        assertTrue(usuarioRepository.existsById(cliente1.getId()), "cliente1 deve permanecer intacto");
        assertTrue(usuarioRepository.existsById(cliente2.getId()), "cliente2 deve permanecer intacto");
    }

    /**
     * 5. A exclusão de CLIENTE passa a aguardar o advisory lock adquirido dentro da
     * própria função SECURITY DEFINER, executada pelo datasource do writer.
     */
    @Test
    void exclusaoDeClienteAguardaAdvisoryLockDaFuncao() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario clienteAlvo = criarCliente("alvo_lock");

        ExecutorService executorA = Executors.newSingleThreadExecutor();
        ExecutorService executorB = Executors.newSingleThreadExecutor();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch liberarLock = new CountDownLatch(1);
        CountDownLatch exclusaoConcluida = new CountDownLatch(1);
        AtomicReference<Throwable> falhaExclusao = new AtomicReference<>();

        // Thread A mantém o advisory lock compartilhado com a função de exclusão.
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
                    autenticarComo(admin);
                    usuarioService.deletarAdministrativo(clienteAlvo.getId());
                } catch (Throwable ex) {
                    falhaExclusao.set(ex);
                } finally {
                    exclusaoConcluida.countDown();
                }
            });

            assertFalse(exclusaoConcluida.await(500, TimeUnit.MILLISECONDS),
                    "A exclusão deve aguardar o advisory lock detido por outra sessão");

            liberarLock.countDown();

            assertTrue(exclusaoConcluida.await(10, TimeUnit.SECONDS),
                    "A exclusão deve concluir após a liberação do lock");
            futureA.get(15, TimeUnit.SECONDS);
            futureB.get(15, TimeUnit.SECONDS);
            assertNull(falhaExclusao.get());
        } finally {
            liberarLock.countDown();
            executorA.shutdownNow();
            executorB.shutdownNow();
        }

        assertFalse(usuarioRepository.existsById(clienteAlvo.getId()),
                "O cliente deve ser excluído após a liberação do lock");
    }
}
