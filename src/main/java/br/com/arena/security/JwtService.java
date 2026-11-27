package br.com.arena.security;

import br.com.arena.model.Usuario;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Emissão e validação de JWT.
 *
 * <p>A claim {@code pwdVersion} espelha {@code Usuario.credentialVersion}. O
 * filtro já carrega o usuário do banco em cada requisição autenticada, portanto
 * a comparação acontece sem consulta adicional. JWTs sem a claim, com valor não
 * numérico, negativo ou divergente são rejeitados (fail-closed).</p>
 */
@Service
public class JwtService {

    static final String CREDENTIAL_VERSION_CLAIM = "pwdVersion";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    /**
     * Representação UTF-8 explícita da chave: a mesma usada por
     * {@link JwtConfigValidator} e por qualquer outro serviço que verifique o
     * token. Assinatura e validação compartilham este único ponto.
     */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(Usuario usuario) {
        return Jwts.builder()
                .subject(usuario.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .claim(CREDENTIAL_VERSION_CLAIM, usuario.getCredentialVersion())
                .signWith(getKey())
                .compact();
    }

    /** Único ponto de verificação de assinatura e expiração do token. */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public Date extractExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    /** Lê e valida a versão de credencial presente no token. */
    public long extractCredentialVersion(Claims claims) {
        Object value = claims.get(CREDENTIAL_VERSION_CLAIM);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("JWT credential version is missing or invalid.");
        }
        long version = number.longValue();
        if (version < 0) {
            throw new IllegalArgumentException("JWT credential version is negative.");
        }
        return version;
    }

    /** Validação usando claims já parseadas, evitando verificações repetidas. */
    public boolean isTokenValid(Claims claims, Usuario usuario) {
        if (claims == null || usuario == null) {
            return false;
        }
        String subject = claims.getSubject();
        Date expirationClaim = claims.getExpiration();
        if (subject == null || !subject.equals(usuario.getEmail())) {
            return false;
        }
        if (expirationClaim == null || !expirationClaim.after(new Date())) {
            return false;
        }
        if (usuario.getCredentialVersion() == null) {
            return false;
        }
        try {
            return extractCredentialVersion(claims) == usuario.getCredentialVersion().longValue();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Método legado mantido para chamadores que não possuem as claims parseadas. */
    public boolean isTokenValid(String token, Usuario usuario) {
        return isTokenValid(parseClaims(token), usuario);
    }
}
