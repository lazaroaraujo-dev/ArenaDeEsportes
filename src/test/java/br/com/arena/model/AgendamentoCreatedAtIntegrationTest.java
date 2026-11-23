package br.com.arena.model;

import br.com.arena.enumerate.Perfil;
import br.com.arena.enumerate.StatusAgendamento;
import br.com.arena.enumerate.TipoQuadra;
import br.com.arena.repository.AgendamentoRepository;
import br.com.arena.repository.QuadraRepository;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IsolatedPostgresIntegrationTest
@Transactional
class AgendamentoCreatedAtIntegrationTest {

    private static final String EXPECTED_TEST_DATABASE = "arena_flyway_test";

    @Autowired
    private AgendamentoRepository agendamentoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private QuadraRepository quadraRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void garantirBancoDeTeste() {
        assertEquals(
                EXPECTED_TEST_DATABASE,
                jdbcTemplate.queryForObject("SELECT current_database()", String.class),
                "Os testes não podem executar em outro database."
        );
    }

    @Test
    void postgresGeraECriaEmRetornaTimestampNoInsert() {
        Agendamento agendamento = criarAgendamento(StatusAgendamento.PENDENTE);
        Instant antesDoInsert = jdbcTemplate.queryForObject(
                "SELECT statement_timestamp()",
                (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant()
        );
        Agendamento persistido = agendamentoRepository.saveAndFlush(agendamento);

        assertNotNull(persistido.getCriadoEm());

        Instant timestampNoBanco = jdbcTemplate.queryForObject(
                "SELECT criado_em FROM agendamento WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant(),
                persistido.getId()
        );
        assertEquals(timestampNoBanco, persistido.getCriadoEm());
        Instant agoraNoBanco = jdbcTemplate.queryForObject(
                "SELECT statement_timestamp()",
                (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant()
        );
        assertFalse(timestampNoBanco.isBefore(antesDoInsert));
        assertFalse(timestampNoBanco.isAfter(agoraNoBanco));

        String defaultDaColuna = jdbcTemplate.queryForObject(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'agendamento'
                  AND column_name = 'criado_em'
                """,
                String.class
        );
        assertTrue(defaultDaColuna.contains("statement_timestamp"));
    }

    @Test
    void criadoEmPermaneceIgualDepoisDeRecarregarDoBanco() {
        Agendamento persistido =
                agendamentoRepository.saveAndFlush(criarAgendamento(StatusAgendamento.PENDENTE));
        Instant timestampOriginal = persistido.getCriadoEm();

        entityManager.clear();

        Agendamento recarregado = agendamentoRepository.findById(persistido.getId())
                .orElseThrow();

        assertEquals(timestampOriginal, recarregado.getCriadoEm());
    }

    @Test
    void atualizarOutrosCamposNaoAlteraCriadoEm() {
        Agendamento persistido =
                agendamentoRepository.saveAndFlush(criarAgendamento(StatusAgendamento.PENDENTE));
        Instant timestampOriginal = persistido.getCriadoEm();

        persistido.setData(persistido.getData().plusDays(1));
        persistido.setHoraInicio(LocalTime.of(11, 0));
        persistido.setHoraFim(LocalTime.of(12, 0));
        agendamentoRepository.saveAndFlush(persistido);
        entityManager.clear();

        Agendamento atualizado = agendamentoRepository.findById(persistido.getId())
                .orElseThrow();

        assertEquals(timestampOriginal, atualizado.getCriadoEm());
        assertEquals(LocalTime.of(11, 0), atualizado.getHoraInicio());
    }

    @Test
    void enumExpiradoPersisteERecarregaEnaoBloqueiaMesmoIntervalo() {
        Agendamento pendente =
                agendamentoRepository.saveAndFlush(criarAgendamento(StatusAgendamento.PENDENTE));
        Agendamento expirado = novoAgendamento(
                StatusAgendamento.EXPIRADO,
                pendente.getUsuario(),
                pendente.getQuadra()
        );
        expirado.setData(pendente.getData());
        expirado.setHoraInicio(pendente.getHoraInicio());
        expirado.setHoraFim(pendente.getHoraFim());
        expirado = agendamentoRepository.saveAndFlush(expirado);
        Long idExpirado = expirado.getId();
        entityManager.clear();

        Agendamento recarregado = agendamentoRepository.findById(idExpirado)
                .orElseThrow();

        assertEquals(StatusAgendamento.EXPIRADO, recarregado.getStatus());
    }

    @Test
    void flywayV1ExisteEConstraintDeSobreposicaoFoiCriada() {
        MigrationInfo[] migrations = flyway.info().applied();
        Long quantidadeConstraints = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM pg_constraint
                WHERE conrelid = 'agendamento'::regclass
                  AND conname = 'ex_agendamento_quadra_periodo_bloqueante'
                  AND contype = 'x'
                """,
                Long.class
        );

        assertEquals(2, migrations.length);
        assertEquals("1", migrations[0].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, migrations[0].getState());
        assertEquals("2", migrations[1].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, migrations[1].getState());
        assertEquals(1L, quantidadeConstraints);
    }

    private Agendamento criarAgendamento(StatusAgendamento status) {
        Usuario usuario = new Usuario();
        usuario.setNome("Usuário Teste");
        usuario.setEmail(UUID.randomUUID() + "@example.invalid");
        usuario.setTelefone("11999999999");
        usuario.setSenha("hash-de-teste");
        usuario.setPerfil(Perfil.CLIENTE);
        usuario = usuarioRepository.saveAndFlush(usuario);

        Quadra quadra = new Quadra();
        quadra.setNome("Quadra Teste");
        quadra.setTipo(TipoQuadra.BEACH_TENNIS);
        quadra.setAtiva(true);
        quadra = quadraRepository.saveAndFlush(quadra);

        return novoAgendamento(status, usuario, quadra);
    }

    private Agendamento novoAgendamento(
            StatusAgendamento status,
            Usuario usuario,
            Quadra quadra) {
        Agendamento agendamento = new Agendamento();
        agendamento.setData(LocalDate.now().plusDays(5));
        agendamento.setHoraInicio(LocalTime.of(9, 0));
        agendamento.setHoraFim(LocalTime.of(10, 0));
        agendamento.setStatus(status);
        agendamento.setUsuario(usuario);
        agendamento.setQuadra(quadra);
        return agendamento;
    }

}
