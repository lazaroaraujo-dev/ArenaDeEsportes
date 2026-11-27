package br.com.arena.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifica o cliente para rate limiting.
 *
 * <p>Padrão: {@code v5.security.trust-forwarded-for=false} — X-Forwarded-For é
 * completamente ignorado e usa-se {@code request.getRemoteAddr()}.</p>
 *
 * <p>Quando habilitado, exige {@code v5.security.trusted-proxies} explícito. O
 * peer imediato precisa ser um proxy confiável; a cadeia XFF é percorrida da
 * direita para a esquerda e o primeiro endereço não confiável é o cliente.
 * Cabeçalhos malformados, ambíguos ou com IP inválido fazem o resolvedor voltar
 * ao {@code remoteAddr}. {@code Forwarded} e {@code X-Real-IP} não são usados.</p>
 */
@Component
public class ClientIdentityResolver {

    private final SecurityHardeningProperties properties;

    public ClientIdentityResolver(SecurityHardeningProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String remote = remoteAddress(request);

        if (!properties.trustForwardedFor()) {
            return remote;
        }

        List<String> trustedProxies = properties.trustedProxies();
        if (trustedProxies.isEmpty()) {
            // Sem proxies explicitamente confiáveis, não há cadeia de confiança
            // válida: mantém o comportamento desabilitado (limitação documentada).
            return remote;
        }
        if (trustedProxies.stream()
                .noneMatch(rule -> IpAddressMatcher.matches(remote, rule))) {
            return remote;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }

        List<String> addresses = new ArrayList<>();
        for (String part : forwarded.split(",")) {
            String candidate = part.trim();
            if (!IpAddressMatcher.isValidAddress(candidate)) {
                return remote;
            }
            addresses.add(IpAddressMatcher.normalize(candidate));
        }
        if (addresses.isEmpty()) {
            return remote;
        }

        for (int i = addresses.size() - 1; i >= 0; i--) {
            String candidate = addresses.get(i);
            if (trustedProxies.stream()
                    .noneMatch(rule -> IpAddressMatcher.matches(candidate, rule))) {
                return candidate;
            }
        }
        return addresses.get(0);
    }

    private static String remoteAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
