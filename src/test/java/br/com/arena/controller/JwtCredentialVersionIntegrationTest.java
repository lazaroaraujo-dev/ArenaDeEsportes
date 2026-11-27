package br.com.arena.controller;

import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.JwtService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** V5.8.1 — claim pwdVersion na autenticação HTTP. */
@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class JwtCredentialVersionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UsuarioRepository usuarioRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Flyway flyway;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate migratorJdbc;
    private String email;

    @BeforeEach
    void setUp() {
        assertEquals("arena_flyway_test",
                jdbcTemplate.queryForObject("SELECT current_database()", String.class));
        migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        email = "jwt_v581_" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.invalid";

        Map<String, Object> state = migratorJdbc.queryForMap(
                "SELECT recovery_required FROM public.admin_lifecycle_state WHERE singleton_id = 1");
        if (Boolean.TRUE.equals(state.get("recovery_required"))) {
            TestPostgresSettings settings = TestPostgresSettings.from(System.getenv());
            DriverManagerDataSource offlineDs = new DriverManagerDataSource();
            offlineDs.setUrl(settings.url());
            offlineDs.setUsername(settings.offlineUsername());
            offlineDs.setPassword(settings.offlinePassword());
            new JdbcTemplate(offlineDs).queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Setup', 'admin_setup_jwt@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup para testes JWT', 'TICKET-JWT'
                    )
                    """,
                    Long.class,
                    passwordEncoder.encode("senha123!")
            );
        }
    }

    private Usuario criarCliente() {
        usuarioRepository.registrarCliente(
                "Cliente JWT", email, "11988887777", passwordEncoder.encode("senha123!"));
        return usuarioRepository.findByEmail(email).orElseThrow();
    }

    private long versaoNoBanco(Long id) {
        Long versao = migratorJdbc.queryForObject(
                "SELECT credential_version FROM public.usuario WHERE id = ?", Long.class, id);
        return versao != null ? versao : -1L;
    }

    @Test
    void cadastroNovoUsuarioRecebeVersaoInicialZero() {
        Usuario usuario = criarCliente();

        assertEquals(0L, versaoNoBanco(usuario.getId()));
        assertEquals(0L, usuario.getCredentialVersion().longValue());
    }

    @Test
    void tokenEmitidoAntesDoIncrementoEhRejeitadoENovoTokenEhAceito() throws Exception {
        Usuario usuario = criarCliente();
        String tokenAntigo = jwtService.generateToken(usuario);

        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAntigo))
                .andExpect(status().isOk());

        migratorJdbc.update(
                "UPDATE public.usuario SET credential_version = 1 WHERE id = ?", usuario.getId());
        assertEquals(1L, versaoNoBanco(usuario.getId()));

        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAntigo))
                .andExpect(status().isForbidden());

        MvcResult login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"senha\":\"senha123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(login.getResponse().getContentAsString());
        String tokenNovo = json.path("token").asText();
        assertTrue(tokenNovo.length() > 20);

        mockMvc.perform(get("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenNovo))
                .andExpect(status().isOk());
    }

    @Test
    void autorizacaoAdminContinuaFuncionandoComClaimDeVersao() throws Exception {
        Long adminId = migratorJdbc.queryForObject(
                """
                INSERT INTO public.usuario (nome, email, telefone, senha, perfil)
                VALUES ('Admin JWT', ?, '11999990000', ?, 'ADMIN')
                RETURNING id
                """,
                Long.class,
                "admin_jwt_" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.invalid",
                passwordEncoder.encode("senha123!")
        );
        Usuario admin = usuarioRepository.findById(adminId).orElseThrow();
        assertEquals(0L, versaoNoBanco(adminId));

        String token = jwtService.generateToken(admin);

        mockMvc.perform(get("/usuarios")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }
}
