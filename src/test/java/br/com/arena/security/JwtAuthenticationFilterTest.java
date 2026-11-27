package br.com.arena.security;

import br.com.arena.enumerate.Perfil;
import br.com.arena.model.Usuario;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** V5.8.1 — rejeição fail-closed de tokens com versão divergente ou inválida. */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "a".repeat(64);
    private static final String OUTRO_SECRET = "b".repeat(64);
    private static final String EMAIL = "usuario@exemplo.invalid";

    private JwtService jwtService;
    private CustomUserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", 3_600_000L);
        userDetailsService = mock(CustomUserDetailsService.class);
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Usuario usuario(Perfil perfil, long versao) {
        return new Usuario(1L, "Usuario", EMAIL, "11999999999", "hash", perfil, versao, List.of());
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private void filtrar(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    @Test
    void tokenValidoDefineAutenticacaoComAuthoritiesDoBanco() throws Exception {
        Usuario admin = usuario(Perfil.ADMIN, 0L);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(admin);

        filtrar(request(jwtService.generateToken(admin)));

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(admin, authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
    }

    @Test
    void tokenComVersaoDivergenteNaoAutentica() throws Exception {
        String token = jwtService.generateToken(usuario(Perfil.CLIENTE, 0L));
        Usuario usuarioAtual = usuario(Perfil.CLIENTE, 1L);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(usuarioAtual);

        filtrar(request(token));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenSemClaimNaoAutentica() throws Exception {
        String token = Jwts.builder()
                .subject(EMAIL)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(usuario(Perfil.CLIENTE, 0L));

        filtrar(request(token));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void falhaAoCarregarUsuarioNaoAutentica() throws Exception {
        String token = jwtService.generateToken(usuario(Perfil.CLIENTE, 0L));
        when(userDetailsService.loadUserByUsername(EMAIL))
                .thenThrow(new UsernameNotFoundException("Usuário não encontrado."));

        filtrar(request(token));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenComAssinaturaInvalidaNaoAutentica() throws Exception {
        JwtService outro = new JwtService();
        ReflectionTestUtils.setField(outro, "secret", OUTRO_SECRET);
        ReflectionTestUtils.setField(outro, "expiration", 3_600_000L);
        String token = outro.generateToken(usuario(Perfil.CLIENTE, 0L));

        filtrar(request(token));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
