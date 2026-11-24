package br.com.arena.controller;

import br.com.arena.dto.usuario.UsuarioRequestDTO;
import br.com.arena.enumerate.Perfil;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.security.JwtService;
import br.com.arena.service.UsuarioService;
import br.com.arena.testsupport.IsolatedPostgresIntegrationTest;
import br.com.arena.testsupport.TestPostgresSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IsolatedPostgresIntegrationTest
@AutoConfigureMockMvc
class UsuarioCadastroAtualizacaoIntegrationTest {

    private static final String EXPECTED_TEST_DATABASE = "arena_flyway_test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioService usuarioService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.flywaydb.core.Flyway flyway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Usuario adminFixture;

    @BeforeEach
    void verificarAmbienteDeTeste() {
        String dbName = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        assertEquals(EXPECTED_TEST_DATABASE, dbName, "Os testes devem rodar exclusivamente em " + EXPECTED_TEST_DATABASE);
    }

    private synchronized Usuario obterOuCriarAdminFixture() {
        if (adminFixture != null && usuarioRepository.existsById(adminFixture.getId())) {
            return adminFixture;
        }

        JdbcTemplate migratorJdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
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

            String senhaHash = passwordEncoder.encode("adminSenha123!");
            Long adminId = offlineJdbc.queryForObject(
                    """
                    SELECT public.h04_recover_admin(
                        NULL, 'Admin Fixture', 'admin_fixture@exemplo.invalid', '11999990000', ?,
                        'test-setup', 'setup inicial para testes', 'TICKET-TEST'
                    )
                    """,
                    Long.class,
                    senhaHash
            );
            adminFixture = usuarioRepository.findById(adminId).orElseThrow();
        } else {
            adminFixture = usuarioRepository.findAll().stream()
                    .filter(u -> u.getPerfil() == Perfil.ADMIN)
                    .findFirst()
                    .orElseGet(() -> {
                        Usuario novoAdmin = new Usuario();
                        novoAdmin.setNome("Admin Extra");
                        novoAdmin.setEmail("admin_extra_" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.invalid");
                        novoAdmin.setTelefone("11999998888");
                        novoAdmin.setSenha(passwordEncoder.encode("adminSenha123!"));
                        novoAdmin.setPerfil(Perfil.ADMIN);
                        return usuarioRepository.save(novoAdmin);
                    });
        }
        return adminFixture;
    }

    private String tokenPara(Usuario usuario) {
        return "Bearer " + jwtService.generateToken(usuario);
    }

    private Usuario criarUsuarioNoBanco(String prefixo, Perfil perfil, String senhaTexto) {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        Usuario usuario = new Usuario();
        usuario.setNome("Usuario " + prefixo + " " + sufixo);
        usuario.setEmail(prefixo.toLowerCase() + "_" + sufixo + "@exemplo.invalid");
        usuario.setTelefone("11988887777");
        usuario.setSenha(passwordEncoder.encode(senhaTexto));
        usuario.setPerfil(perfil);
        return usuarioRepository.save(usuario);
    }

    @Test
    void cadastroPublicoCriaClienteComSucesso() throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String json = """
                {
                    "nome": "Novo Cliente %s",
                    "email": "cliente_%s@exemplo.invalid",
                    "telefone": "11999990000",
                    "senha": "senhaSegura123!"
                }
                """.formatted(sufixo, sufixo);

        MvcResult result = mockMvc.perform(post("/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.nome").value("Novo Cliente " + sufixo))
                .andExpect(jsonPath("$.perfil").value("CLIENTE"))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        Long id = responseNode.get("id").asLong();

        Usuario usuarioNoBanco = usuarioRepository.findById(id).orElseThrow();
        assertEquals(Perfil.CLIENTE, usuarioNoBanco.getPerfil(), "Cadastro público deve sempre criar perfil CLIENTE");
        assertTrue(passwordEncoder.matches("senhaSegura123!", usuarioNoBanco.getSenha()), "Senha deve ser armazenada com hash");
    }

    @Test
    void cadastroPublicoIgnoraTentativaDeInjetarPerfilAdmin() throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        // Tentativa maliciosa de enviar perfil ADMIN no payload público
        String jsonComPerfilAdmin = """
                {
                    "nome": "Atacante %s",
                    "email": "hacker_%s@exemplo.invalid",
                    "telefone": "11999991111",
                    "senha": "senhaSegura123!",
                    "perfil": "ADMIN"
                }
                """.formatted(sufixo, sufixo);

        MvcResult result = mockMvc.perform(post("/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonComPerfilAdmin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perfil").value("CLIENTE"))
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        Long id = responseNode.get("id").asLong();

        Usuario usuarioSalvo = usuarioRepository.findById(id).orElseThrow();
        assertEquals(Perfil.CLIENTE, usuarioSalvo.getPerfil(), "Perfil deve ser forçado para CLIENTE mesmo com payload malicioso");
    }

    @Test
    void cadastroPublicoRejeitaDadosInvalidos() throws Exception {
        // Nome em branco
        mockMvc.perform(post("/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nome": "",
                                    "email": "invalido@exemplo.invalid",
                                    "telefone": "11999992222",
                                    "senha": "senhaSegura123!"
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Email inválido
        mockMvc.perform(post("/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nome": "Nome Valido",
                                    "email": "email-invalido-sem-arroba",
                                    "telefone": "11999992222",
                                    "senha": "senhaSegura123!"
                                }
                                """))
                .andExpect(status().isBadRequest());

        // Senha curta (< 6 caracteres)
        mockMvc.perform(post("/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "nome": "Nome Valido",
                                    "email": "valido@exemplo.invalid",
                                    "telefone": "11999992222",
                                    "senha": "123"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cadastroPublicoRejeitaEmailDuplicado() throws Exception {
        Usuario existente = criarUsuarioNoBanco("duplicado", Perfil.CLIENTE, "senha123");

        String json = """
                {
                    "nome": "Outro Nome",
                    "email": "%s",
                    "telefone": "11999993333",
                    "senha": "outraSenha123!"
                }
                """.formatted(existente.getEmail());

        mockMvc.perform(post("/usuarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("E-mail já cadastrado."));
    }

    @Test
    void clienteAtualizaDadosPessoaisComSucesso() throws Exception {
        Usuario cliente = criarUsuarioNoBanco("cliente_me", Perfil.CLIENTE, "senhaAtual123!");
        String token = tokenPara(cliente);

        String updateJson = """
                {
                    "nome": "Nome Atualizado pelo Cliente",
                    "email": "%s",
                    "telefone": "11988889999"
                }
                """.formatted(cliente.getEmail());

        mockMvc.perform(put("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Nome Atualizado pelo Cliente"))
                .andExpect(jsonPath("$.telefone").value("11988889999"))
                .andExpect(jsonPath("$.perfil").value("CLIENTE"));

        Usuario atualizado = usuarioRepository.findById(cliente.getId()).orElseThrow();
        assertEquals("Nome Atualizado pelo Cliente", atualizado.getNome());
        assertEquals("11988889999", atualizado.getTelefone());
    }

    @Test
    void clienteNaoAlteraPerfilNemSenhaViaAtualizacaoPessoal() throws Exception {
        Usuario cliente = criarUsuarioNoBanco("cliente_seg", Perfil.CLIENTE, "senhaOriginal123!");
        String token = tokenPara(cliente);

        // Tentativa de alterar perfil e senha através do PUT /usuarios/me
        String jsonMalicioso = """
                {
                    "nome": "Nome Modificado",
                    "email": "%s",
                    "telefone": "11977778888",
                    "perfil": "ADMIN",
                    "senha": "novaSenhaTentativa123!"
                }
                """.formatted(cliente.getEmail());

        mockMvc.perform(put("/usuarios/me")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMalicioso))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("CLIENTE"));

        Usuario persistido = usuarioRepository.findById(cliente.getId()).orElseThrow();
        assertEquals(Perfil.CLIENTE, persistido.getPerfil(), "Perfil do cliente não pode ser alterado por PUT /usuarios/me");
        assertTrue(passwordEncoder.matches("senhaOriginal123!", persistido.getSenha()), "Senha não pode ser alterada por PUT /usuarios/me");
        assertFalse(passwordEncoder.matches("novaSenhaTentativa123!", persistido.getSenha()), "Senha nova não deve ter sido atribuída");
    }

    @Test
    void adminAtualizaDadosPessoaisDeOutroUsuario() throws Exception {
        Usuario admin = obterOuCriarAdminFixture();
        Usuario cliente = criarUsuarioNoBanco("cliente_alvo", Perfil.CLIENTE, "clienteSenha123!");
        String tokenAdmin = tokenPara(admin);

        String jsonUpdate = """
                {
                    "nome": "Nome Corrigido pelo Admin",
                    "email": "%s",
                    "telefone": "11966665555"
                }
                """.formatted(cliente.getEmail());

        mockMvc.perform(put("/usuarios/" + cliente.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonUpdate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Nome Corrigido pelo Admin"))
                .andExpect(jsonPath("$.telefone").value("11966665555"))
                .andExpect(jsonPath("$.perfil").value("CLIENTE"));

        Usuario alvoNoBanco = usuarioRepository.findById(cliente.getId()).orElseThrow();
        assertEquals("Nome Corrigido pelo Admin", alvoNoBanco.getNome());
        assertEquals(Perfil.CLIENTE, alvoNoBanco.getPerfil());
        assertTrue(passwordEncoder.matches("clienteSenha123!", alvoNoBanco.getSenha()), "Senha do cliente deve permanecer intacta");
    }

    @Test
    void adminNaoAlteraPerfilNemSenhaViaPutUsuario() throws Exception {
        Usuario admin = obterOuCriarAdminFixture();
        Usuario cliente = criarUsuarioNoBanco("cliente_perfil_test", Perfil.CLIENTE, "clienteSenha123!");
        String tokenAdmin = tokenPara(admin);

        // Mesmo com token de ADMIN, PUT /usuarios/{id} é apenas para dados cadastrais; perfil e senha não devem mudar por aí
        String jsonTentativa = """
                {
                    "nome": "Novo Nome",
                    "email": "%s",
                    "telefone": "11955554444",
                    "perfil": "ADMIN",
                    "senha": "novaSenhaViaAdmin123!"
                }
                """.formatted(cliente.getEmail());

        mockMvc.perform(put("/usuarios/" + cliente.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonTentativa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil").value("CLIENTE"));

        Usuario alvo = usuarioRepository.findById(cliente.getId()).orElseThrow();
        assertEquals(Perfil.CLIENTE, alvo.getPerfil(), "PUT /usuarios/{id} não altera perfil (exige PATCH /usuarios/{id}/perfil)");
        assertTrue(passwordEncoder.matches("clienteSenha123!", alvo.getSenha()), "PUT /usuarios/{id} não altera senha");
    }

    // -------------------------------------------------------------------------
    // LAC-04: Métodos legados de cadastro e atualização via UsuarioRequestDTO
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("deprecation")
    void metodoLegadoSalvarComRequestDTOForcaPerfilCliente() {
        // LAC-04-A: chamada DIRETA ao método legado salvar(UsuarioRequestDTO).
        // O DTO legado não possui campo "perfil"; a garantia testada é a do serviço:
        // todo usuário criado por essa via continua sendo CLIENTE.
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        UsuarioRequestDTO dto = UsuarioRequestDTO.builder()
                .nome("Legado Salvar " + sufixo)
                .email("legado_salvar_" + sufixo + "@exemplo.invalid")
                .telefone("11988880001")
                .senha("senhaLegado123!")
                .build();

        var response = usuarioService.salvar(dto);

        assertEquals(Perfil.CLIENTE, response.getPerfil(),
                "O retorno do método legado salvar deve indicar perfil CLIENTE");

        Usuario criado = usuarioRepository.findById(response.getId()).orElseThrow();
        assertEquals(Perfil.CLIENTE, criado.getPerfil(),
                "O método legado salvar(UsuarioRequestDTO) deve forçar perfil CLIENTE");
        assertTrue(passwordEncoder.matches("senhaLegado123!", criado.getSenha()),
                "A senha informada no DTO legado deve ser armazenada com hash correto");
    }

    @Test
    @SuppressWarnings("deprecation")
    void metodoLegadoAtualizarComRequestDTOIgnoraSenhaENaoAlteraPerfil() {
        // LAC-04-B: chamada DIRETA ao método legado atualizar(Long, UsuarioRequestDTO).
        // O DTO legado possui senha, mas o serviço converte para UsuarioUpdateDTO e
        // não repassa esse campo; também não existe campo "perfil" no DTO.
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String senhaOriginal = "senhaOriginal_legado123!";
        String novaSenhaTentada = "novaSenhaLegado123!";
        Usuario cliente = criarUsuarioNoBanco("legado_atualizar_" + sufixo, Perfil.CLIENTE, senhaOriginal);

        UsuarioRequestDTO dto = UsuarioRequestDTO.builder()
                .nome("Nome Atualizado Legado " + sufixo)
                .email(cliente.getEmail())
                .telefone("11977770001")
                .senha(novaSenhaTentada)
                .build();

        var response = usuarioService.atualizar(cliente.getId(), dto);

        assertEquals(Perfil.CLIENTE, response.getPerfil(),
                "O retorno do método legado atualizar não pode indicar promoção de perfil");

        Usuario persistido = usuarioRepository.findById(cliente.getId()).orElseThrow();
        assertEquals("Nome Atualizado Legado " + sufixo, persistido.getNome(),
                "O nome informado no DTO legado deve ser atualizado");
        assertEquals("11977770001", persistido.getTelefone(),
                "O telefone informado no DTO legado deve ser atualizado");
        assertEquals(Perfil.CLIENTE, persistido.getPerfil(),
                "O método legado atualizar não pode alterar o perfil do usuário");
        assertTrue(passwordEncoder.matches(senhaOriginal, persistido.getSenha()),
                "A senha original deve permanecer intacta após chamada direta ao método legado");
        assertFalse(passwordEncoder.matches(novaSenhaTentada, persistido.getSenha()),
                "A senha presente no DTO legado não pode ser aplicada pelo serviço");
    }

    @Test
    void clienteNaoPodeAtualizarOutroUsuario() throws Exception {
        Usuario cliente1 = criarUsuarioNoBanco("cliente_orig", Perfil.CLIENTE, "senha123");
        Usuario cliente2 = criarUsuarioNoBanco("cliente_dest", Perfil.CLIENTE, "senha123");
        String tokenCliente1 = tokenPara(cliente1);

        String json = """
                {
                    "nome": "Nome Invasor",
                    "email": "%s",
                    "telefone": "11944443333"
                }
                """.formatted(cliente2.getEmail());

        mockMvc.perform(put("/usuarios/" + cliente2.getId())
                        .header(HttpHeaders.AUTHORIZATION, tokenCliente1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isForbidden());
    }
}
