package br.com.arena.security;

import br.com.arena.enumerate.Perfil;
import br.com.arena.model.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V5.8.1 — emissão, claim pwdVersion e validação fail-closed. */
class JwtServiceTest {

    private static final String SECRET = "a".repeat(64);
    private static final String OUTRO_SECRET = "b".repeat(64);
    /** 52 bytes em UTF-8: mesma representação usada pelo JwtConfigValidator. */
    private static final String SECRET_UTF8 = "chave-áéí-".repeat(4);
    private static final long EXPIRATION = 3_600_000L;
    private static final String EMAIL = "usuario@exemplo.invalid";

    private JwtService service;

    @BeforeEach
    void setUp() {
        service = new JwtService();
        ReflectionTestUtils.setField(service, "secret", SECRET);
        ReflectionTestUtils.setField(service, "expiration", EXPIRATION);
    }

    private static Usuario usuario(long versao) {
        return new Usuario(1L, "Usuario", EMAIL, "11999999999", "hash",
                Perfil.CLIENTE, versao, List.of());
    }

    private static JwtService servicoCom(String secret) {
        JwtService servico = new JwtService();
        ReflectionTestUtils.setField(servico, "secret", secret);
        ReflectionTestUtils.setField(servico, "expiration", EXPIRATION);
        return servico;
    }

    private static String tokenAssinado(String secret, Charset charset) {
        return Jwts.builder()
                .subject(EMAIL)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .claim(JwtService.CREDENTIAL_VERSION_CLAIM, 0L)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(charset)))
                .compact();
    }

    private String tokenSemClaim(Object claimValue) {
        return Jwts.builder()
                .subject(EMAIL)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .claim(JwtService.CREDENTIAL_VERSION_CLAIM, claimValue)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void tokenContemPwdVersionIgualVersaoAtualDoUsuario() {
        String token = service.generateToken(usuario(0L));

        Claims claims = service.parseClaims(token);

        assertEquals(0L, service.extractCredentialVersion(claims));
        assertEquals(EMAIL, claims.getSubject());
    }

    @Test
    void tokenComVersaoCorrespondenteEhAceito() {
        String token = service.generateToken(usuario(7L));

        assertTrue(service.isTokenValid(service.parseClaims(token), usuario(7L)));
        assertTrue(service.isTokenValid(token, usuario(7L)));
    }

    @Test
    void tokenDaVersaoZeroEhRejeitadoQuandoUsuarioPassaParaVersaoUm() {
        String tokenAntigo = service.generateToken(usuario(0L));

        assertFalse(service.isTokenValid(service.parseClaims(tokenAntigo), usuario(1L)));
    }

    @Test
    void tokenSemClaimEhRejeitado() {
        String token = Jwts.builder()
                .subject(EMAIL)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = service.parseClaims(token);

        assertFalse(service.isTokenValid(claims, usuario(0L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.extractCredentialVersion(claims));
    }

    @Test
    void tokenComClaimNaoNumericaEhRejeitado() {
        Claims claims = service.parseClaims(tokenSemClaim("abc"));

        assertFalse(service.isTokenValid(claims, usuario(0L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.extractCredentialVersion(claims));
    }

    @Test
    void tokenComVersaoNegativaEhRejeitado() {
        Claims claims = service.parseClaims(tokenSemClaim(-1));

        assertFalse(service.isTokenValid(claims, usuario(0L)));
        assertThrows(IllegalArgumentException.class,
                () -> service.extractCredentialVersion(claims));
    }

    @Test
    void tokenExpiradoContinuaRejeitado() {
        JwtService expirado = new JwtService();
        ReflectionTestUtils.setField(expirado, "secret", SECRET);
        ReflectionTestUtils.setField(expirado, "expiration", -1_000L);

        String token = expirado.generateToken(usuario(0L));

        assertThrows(JwtException.class, () -> service.parseClaims(token));
    }

    @Test
    void tokenComAssinaturaInvalidaContinuaRejeitado() {
        JwtService outro = new JwtService();
        ReflectionTestUtils.setField(outro, "secret", OUTRO_SECRET);
        ReflectionTestUtils.setField(outro, "expiration", EXPIRATION);

        String token = outro.generateToken(usuario(0L));

        assertThrows(JwtException.class, () -> service.parseClaims(token));
    }

    @Test
    void chaveNaoAsciiUsaUtf8NaGeracaoEValidacao() {
        JwtService utf8 = servicoCom(SECRET_UTF8);

        String token = utf8.generateToken(usuario(0L));

        assertEquals(EMAIL, utf8.parseClaims(token).getSubject());
        assertTrue(utf8.isTokenValid(token, usuario(0L)));
    }

    @Test
    void tokenAssinadoComBytesUtf8DaChaveEhAceitoPelosDoisCaminhos() {
        JwtService utf8 = servicoCom(SECRET_UTF8);

        String token = tokenAssinado(SECRET_UTF8, StandardCharsets.UTF_8);

        assertEquals(0L, utf8.extractCredentialVersion(utf8.parseClaims(token)));
        assertTrue(utf8.isTokenValid(token, usuario(0L)));
    }

    @Test
    void tokenAssinadoComOutroCharsetDaChaveEhRejeitado() {
        JwtService utf8 = servicoCom(SECRET_UTF8);

        String token = tokenAssinado(SECRET_UTF8, StandardCharsets.ISO_8859_1);

        assertThrows(JwtException.class, () -> utf8.parseClaims(token));
    }

    @Test
    void geracaoEValidacaoCompartilhamMesmosBytesDaChave() {
        JwtService emissor = servicoCom(SECRET_UTF8);
        JwtService validador = servicoCom(SECRET_UTF8);

        assertTrue(validador.isTokenValid(emissor.generateToken(usuario(3L)), usuario(3L)));
        assertFalse(validador.isTokenValid(emissor.generateToken(usuario(3L)), usuario(4L)));
    }
}
