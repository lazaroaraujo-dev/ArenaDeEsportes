package br.com.arena.testsupport;

import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

/**
 * Fixtures de usuario compatíveis com a arquitetura V4.1+.
 *
 * <p>CLIENTE é criado por {@code arena_runtime} através da função SECURITY DEFINER
 * {@code h04_registrar_cliente(...)} — o runtime não tem INSERT direto em usuario.</p>
 *
 * <p>ADMIN é preparado por {@code arena_migrator} (dono das tabelas), depois de
 * garantir que o singleton administrativo esteja em estado ativo. Se o banco ainda
 * estiver em PENDING ou RECOVERY_REQUIRED, a primeira conta ADMIN é criada por
 * {@code arena_offline_operator} via bootstrap/recovery.</p>
 *
 * <p>Nenhum privilégio adicional é concedido ao runtime.</p>
 */
public final class TestUsuarioFixture {

    private final UsuarioRepository usuarioRepository;
    private final JdbcTemplate migratorJdbc;
    private final JdbcTemplate offlineJdbc;

    public TestUsuarioFixture(UsuarioRepository usuarioRepository, Flyway flyway) {
        this.usuarioRepository = usuarioRepository;
        this.migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());

        TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
        DriverManagerDataSource offlineDataSource = new DriverManagerDataSource();
        offlineDataSource.setUrl(settings.url());
        offlineDataSource.setUsername(settings.offlineUsername());
        offlineDataSource.setPassword(settings.offlinePassword());
        this.offlineJdbc = new JdbcTemplate(offlineDataSource);
    }

    /** CLIENTE via arena_runtime + h04_registrar_cliente(...). */
    public Usuario criarCliente(String nome, String email, String telefone, String senhaHash) {
        Long id = usuarioRepository.registrarCliente(nome, email, telefone, senhaHash);
        if (id == null) {
            throw new IllegalStateException("h04_registrar_cliente não retornou id.");
        }
        return usuarioRepository.findById(id).orElseThrow();
    }

    /** ADMIN via arena_migrator, com estado administrativo previamente ativado. */
    public Usuario criarAdmin(String nome, String email, String telefone, String senhaHash) {
        garantirEstadoAdministrativoAtivo();

        Long id = migratorJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES (?, ?, ?, ?, 'ADMIN')
                RETURNING id
                """,
                Long.class,
                nome, email, telefone, senhaHash
        );
        return usuarioRepository.findById(id).orElseThrow();
    }

    private void garantirEstadoAdministrativoAtivo() {
        Map<String, Object> state = migratorJdbc.queryForMap(
                """
                SELECT bootstrap_consumed, recovery_required
                  FROM public.admin_lifecycle_state
                 WHERE singleton_id = 1
                """
        );
        boolean bootstrapConsumed = Boolean.TRUE.equals(state.get("bootstrap_consumed"));
        boolean recoveryRequired = Boolean.TRUE.equals(state.get("recovery_required"));

        if (bootstrapConsumed && !recoveryRequired) {
            return;
        }

        if (recoveryRequired) {
            offlineJdbc.queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Recuperado', 'admin.recuperado@exemplo.invalid',
                        '11999990000', ?, 'test-fixture', 'preparacao de fixture', 'TICKET-FIXTURE'
                    )
                    """,
                    Long.class,
                    "hash-de-teste"
            );
            return;
        }

        offlineJdbc.queryForObject(
                """
                SELECT public.h04_bootstrap_first_admin(
                    'Admin Inicial', 'admin.inicial@exemplo.invalid',
                    '11999990000', ?, 'test-fixture'
                )
                """,
                Long.class,
                "hash-de-teste"
        );
    }
}
