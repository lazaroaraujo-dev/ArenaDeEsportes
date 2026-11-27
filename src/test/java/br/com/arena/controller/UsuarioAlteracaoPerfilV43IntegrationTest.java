package br.com.arena.controller;

import br.com.arena.enumerate.Perfil;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.JwtService;
import br.com.arena.service.UsuarioService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V4.3 — função segura de perfil e atualização pessoal determinística. */
@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class UsuarioAlteracaoPerfilV43IntegrationTest {

    private static final String EXPECTED_DATABASE = "arena_flyway_test";

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Flyway flyway;
    @Autowired private UsuarioService usuarioService;

    private JdbcTemplate migratorJdbc;

    @BeforeEach
    void setUp() {
        assertEquals(EXPECTED_DATABASE,
                jdbcTemplate.queryForObject("SELECT current_database()", String.class));
        migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        garantirEstadoAtivoComAdmin();
        limparAdminsSobressalentes();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private synchronized void garantirEstadoAtivoComAdmin() {
        Map<String, Object> state = migratorJdbc.queryForMap(
                "SELECT recovery_required FROM public.admin_lifecycle_state WHERE singleton_id = 1"
        );
        if (Boolean.TRUE.equals(state.get("recovery_required"))) {
            TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
            DriverManagerDataSource offlineDs = new DriverManagerDataSource();
            offlineDs.setUrl(settings.url());
            offlineDs.setUsername(settings.offlineUsername());
            offlineDs.setPassword(settings.offlinePassword());
            new JdbcTemplate(offlineDs).queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Master', 'admin_master@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup inicial para testes', 'TICKET-TEST'
                    )
                    """,
                    Long.class,
                    passwordEncoder.encode("adminMaster123!")
            );
        }
    }

    private void limparAdminsSobressalentes() {
        List<Usuario> admins = usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN).toList();
        for (int i = 1; i < admins.size(); i++) {
            migratorJdbc.update(
                    "UPDATE public.usuario SET perfil = 'CLIENTE' WHERE id = ?", admins.get(i).getId());
        }
    }

    private Usuario obterPrimeiroAdmin() {
        return usuarioRepository.findAll().stream()
                .filter(u -> u.getPerfil() == Perfil.ADMIN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Nenhum ADMIN no banco de testes"));
    }

    private Usuario criarUsuario(String prefixo, Perfil perfil) {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String email = prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid";
        Long id = migratorJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES (?, ?, ?, ?, ?::varchar)
                RETURNING id
                """,
                Long.class,
                "Usuario " + prefixo, email, "11988887777", "hash-inicial", perfil.name()
        );
        return new Usuario(id, "Usuario " + prefixo, email, "11988887777",
                "hash-inicial", perfil, 0L, List.of());
    }

    private String tokenPara(Usuario usuario) {
        return "Bearer " + jwtService.generateToken(usuario);
    }

    private void autenticarComo(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities()));
    }

    private long contarAuditorias(String operation, String outcome, String errorCode) {
        Long count = migratorJdbc.queryForObject(
                """
                SELECT count(*) FROM public.admin_audit_event
                WHERE operation = ? AND outcome = ? AND error_code IS NOT DISTINCT FROM ?
                """,
                Long.class, operation, outcome, errorCode
        );
        return count != null ? count : 0L;
    }

    private long contadorAdministrativo() {
        Long count = migratorJdbc.queryForObject(
                "SELECT admin_count FROM public.admin_lifecycle_state WHERE singleton_id = 1", Long.class);
        return count != null ? count : 0L;
    }

    @Test
    void promocaoEAuditoriaPelaFuncaoDoWriter() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario cliente = criarUsuario("promover_v43", Perfil.CLIENTE);
        long auditoriasAntes = contarAuditorias("PROMOTE", "SUCCEEDED", null);
        long contadorAntes = contadorAdministrativo();

        mockMvc.perform(patch("/usuarios/" + cliente.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfil\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("ADMIN"));

        assertEquals(Perfil.ADMIN, usuarioRepository.findById(cliente.getId()).orElseThrow().getPerfil());
        assertEquals(contadorAntes + 1, contadorAdministrativo());
        assertEquals(auditoriasAntes + 1, contarAuditorias("PROMOTE", "SUCCEEDED", null));
    }

    @Test
    void rebaixamentoComOutroAdminMantemContadorSincronizado() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        Usuario outroAdmin = criarUsuario("rebaixar_v43", Perfil.ADMIN);
        long contadorAntes = contadorAdministrativo();

        mockMvc.perform(patch("/usuarios/" + outroAdmin.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfil\":\"CLIENTE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("CLIENTE"));

        assertEquals(contadorAntes - 1, contadorAdministrativo());
        assertEquals(1L, contarAuditorias("DEMOTE", "SUCCEEDED", null));
    }

    @Test
    void ultimoAdminNaoPodeSerRebaixadoEAuditoriaPermaneceCommitada() throws Exception {
        Usuario ultimoAdmin = obterPrimeiroAdmin();
        long auditoriasAntes = contarAuditorias("DEMOTE", "REJECTED", "LAST_ADMIN");

        mockMvc.perform(patch("/usuarios/" + ultimoAdmin.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(ultimoAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfil\":\"CLIENTE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("Não é permitido rebaixar o único administrador do sistema."));

        assertEquals(Perfil.ADMIN, usuarioRepository.findById(ultimoAdmin.getId()).orElseThrow().getPerfil());
        assertEquals(auditoriasAntes + 1, contarAuditorias("DEMOTE", "REJECTED", "LAST_ADMIN"));
        assertEquals(1L, contadorAdministrativo());
    }

    @Test
    void noOpNaoGeraAuditoriaNemMutacao() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        long totalAntes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class);

        mockMvc.perform(patch("/usuarios/" + admin.getId() + "/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfil\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("ADMIN"));

        assertEquals(totalAntes, migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class));
    }

    @Test
    void alvoInexistenteRetorna404SemAuditoria() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        long totalAntes = migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class);

        mockMvc.perform(patch("/usuarios/999999999/perfil")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfil\":\"ADMIN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Usuário não encontrado."));

        assertEquals(totalAntes, migratorJdbc.queryForObject(
                "SELECT count(*) FROM public.admin_audit_event", Long.class));
    }

    @Test
    void atorInexistenteNoPrincipalEhNegadoPeloWriter() {
        Usuario fantasma = new Usuario(987654321L, "Fantasma", "fantasma@exemplo.invalid",
                "11999999999", "hash", Perfil.ADMIN, 0L, List.of());
        autenticarComo(fantasma);

        assertThrows(AccessDeniedException.class,
                () -> usuarioService.alterarPerfil(
                        obterPrimeiroAdmin().getId(),
                        br.com.arena.dto.usuario.UsuarioPerfilDTO.builder().perfil(Perfil.CLIENTE).build()));
    }

    @Test
    void estadoInconsistenteRetorna500SemMascarar() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        migratorJdbc.update("UPDATE public.admin_lifecycle_state SET admin_count = 9999 WHERE singleton_id = 1");
        try {
            mockMvc.perform(patch("/usuarios/" + admin.getId() + "/perfil")
                            .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"perfil\":\"ADMIN\"}"))
                    .andExpect(status().isInternalServerError());
        } finally {
            long real = usuarioRepository.findAll().stream()
                    .filter(u -> u.getPerfil() == Perfil.ADMIN).count();
            migratorJdbc.update(
                    "UPDATE public.admin_lifecycle_state SET admin_count = ? WHERE singleton_id = 1", real);
        }
    }

    @Test
    void atualizacaoPessoalAlteraSomenteNomeEmailETelefone() throws Exception {
        Usuario admin = obterPrimeiroAdmin();
        String senhaAntes = migratorJdbc.queryForObject(
                "SELECT senha FROM public.usuario WHERE id = ?", String.class, admin.getId());

        String json = new ObjectMapper().writeValueAsString(Map.of(
                "nome", "Nome Atualizado V43",
                "email", "atualizado_v43@exemplo.invalid",
                "telefone", "11977770000"
        ));

        mockMvc.perform(put("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, tokenPara(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Nome Atualizado V43"))
                .andExpect(jsonPath("$.perfil").value("ADMIN"));

        Map<String, Object> linha = migratorJdbc.queryForMap(
                "SELECT nome, email, telefone, senha, perfil FROM public.usuario WHERE id = ?", admin.getId());
        assertEquals("Nome Atualizado V43", linha.get("nome"));
        assertEquals("atualizado_v43@exemplo.invalid", linha.get("email"));
        assertEquals("11977770000", linha.get("telefone"));
        assertEquals(senhaAntes, linha.get("senha"), "senha não pode ser alterada");
        assertEquals("ADMIN", linha.get("perfil"), "perfil não pode ser alterado");
    }
}
