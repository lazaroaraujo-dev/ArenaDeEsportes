package br.com.arena.security;

import br.com.arena.exception.RateLimitSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Rate limiting server-side para os endpoints sensíveis definidos na V5:
 * POST /auth/login e POST /usuarios (cadastro público).
 *
 * <p>A identificação da rota usa o mesmo processamento de caminho do Spring MVC
 * ({@link ServletRequestPathUtils}), removendo context path, variáveis de matriz
 * e decodificando segmentos. Assim, variantes como {@code /auth/login;x=1},
 * {@code /auth/%6cogin} ou um context path não conseguem escapar do limite nem
 * fazê-lo incidir sobre rotas diferentes.</p>
 *
 * <p>Não confia em X-Forwarded-For a menos que configurado explicitamente; consulta
 * {@link ClientIdentityResolver}. Respostas 429 não revelam detalhes do cliente.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final ClientRateLimiter rateLimiter;
    private final ClientIdentityResolver identityResolver;

    public RateLimitFilter(ClientRateLimiter rateLimiter, ClientIdentityResolver identityResolver) {
        this.rateLimiter = rateLimiter;
        this.identityResolver = identityResolver;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return bucketFor(request) == null;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String bucket = bucketFor(request);
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        ClientRateLimiter.RateLimitDecision decision =
                rateLimiter.tryAcquireWithRetryAfter(bucket, identityResolver.resolve(request));
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader(
                HttpHeaders.RETRY_AFTER,
                Long.toString(Math.max(1L, decision.retryAfterSeconds()))
        );
        response.setHeader(
                RateLimitSource.HEADER_NAME,
                RateLimitSource.REQUEST_RATE_LIMIT.headerValue()
        );
        String timestamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now());
        response.getWriter().write("""
                {"timestamp":"%s","status":429,"error":"Limite de requisições excedido. Tente novamente mais tarde."}
                """.formatted(timestamp));
    }

    private static String bucketFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = normalizedPathWithinApplication(request);
        if (path == null) {
            return null;
        }
        if ("/auth/login".equals(path)) {
            return "login";
        }
        if ("/usuarios".equals(path)) {
            return "register";
        }
        return null;
    }

    /**
     * Reconstrói o caminho a partir dos elementos já processados pelo Spring:
     * sem context path, com variáveis de matriz removidas e segmentos decodificados.
     * Retorna {@code null} quando o caminho é ambíguo ou inválido — nesse caso o
     * Spring MVC decide o destino (normalmente 400/404) e o filtro não interfere.
     */
    private static String normalizedPathWithinApplication(HttpServletRequest request) {
        try {
            RequestPath requestPath = ServletRequestPathUtils.parseAndCache(request);
            if (requestPath.pathWithinApplication().value().contains("//")) {
                return null;
            }

            StringBuilder builder = new StringBuilder();
            boolean trailingSeparator = false;

            for (PathContainer.Element element : requestPath.pathWithinApplication().elements()) {
                if (element instanceof PathContainer.PathSegment segment) {
                    String value = segment.valueToMatch();
                    // Um segmento decodificado que contém '/' não corresponde a
                    // uma rota literal do MVC; não cria ambiguidade artificial.
                    if (value.contains("/")) {
                        return null;
                    }
                    builder.append('/').append(value);
                    trailingSeparator = false;
                } else {
                    trailingSeparator = true;
                }
            }

            if (trailingSeparator && builder.length() > 0) {
                builder.append('/');
            }
            return builder.length() == 0 ? "/" : builder.toString();
        } catch (RuntimeException ex) {
            // Path malformado: o MVC também rejeitará a requisição.
            return null;
        }
    }
}
