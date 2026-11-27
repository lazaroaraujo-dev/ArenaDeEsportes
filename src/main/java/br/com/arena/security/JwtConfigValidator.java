package br.com.arena.security;

import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validação fail-fast da configuração JWT na inicialização da aplicação.
 *
 * <p>Não altera algoritmo nem invalida tokens: o jjwt continua escolhendo o
 * algoritmo HMAC compatível com o tamanho da chave, exatamente como
 * {@link JwtService} já faz. Nenhum valor secreto é incluído nas mensagens.</p>
 *
 * <p>Decisão A3: a ausência de profile ou um profile não reconhecido é tratada
 * como produção para efeito de validação do segredo. Somente perfis
 * explicitamente de desenvolvimento/teste ou a propriedade explícita
 * {@code v5.security.production=false} desativam a checagem de valores de
 * desenvolvimento. Isso é fail-closed e evita que um deploy sem profile aceite
 * segredo fraco por omissão.</p>
 *
 * <p>A verificação adicional de padrões triviais (V5.9.1/M8) é determinística e
 * independe de profile: rejeita segredos degenerados como um único caractere
 * repetido, diversidade muito baixa, blocos curtos repetidos ou sequências
 * monotônicas longas. Não é uma estimativa de entropia e não impõe formato —
 * segredos aleatórios arbitrários continuam aceitos.</p>
 */
@Component
public class JwtConfigValidator {

    private static final int MINIMUM_HMAC_KEY_BYTES = 32;
    private static final long MINIMUM_EXPIRATION_MS = 60_000L;
    private static final long MAXIMUM_EXPIRATION_MS = 604_800_000L;

    /** Limiares conservadores: segredos aleatórios reais ficam bem acima deles. */
    private static final int MINIMUM_DISTINCT_CODE_POINTS = 6;
    private static final double MAXIMUM_SINGLE_CODE_POINT_RATIO = 0.75;
    private static final int MAXIMUM_REPEATED_BLOCK_LENGTH = 8;
    private static final int MINIMUM_SEQUENCE_RUN = 10;

    private static final String[] INSECURE_MARKERS = {
            "secret", "chave", "dev", "test", "exemplo", "replace", "troque", "123456"
    };

    private static final Set<String> PRODUCTION_PROFILES = Set.of(
            "prod", "production", "producao", "prd", "release", "live"
    );
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of(
            "dev", "development", "test", "tests", "local", "qa"
    );

    @Autowired
    public JwtConfigValidator(
            @Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration:}") Long expiration,
            @Value("${spring.profiles.active:}") String activeProfiles,
            @Value("${v5.security.production:}") String productionFlag) {

        validate(secret, expiration, activeProfiles, productionFlag);
    }

    /** Construtor de teste: mantém as chamadas existentes com três argumentos. */
    JwtConfigValidator(String secret, Long expiration, String activeProfiles) {
        this(secret, expiration, activeProfiles, "");
    }

    private static void validate(
            String secret, Long expiration, String activeProfiles, String productionFlag) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret is required.");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MINIMUM_HMAC_KEY_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret is too short for the configured HMAC algorithm.");
        }

        try {
            Keys.hmacShaKeyFor(keyBytes);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("jwt.secret is incompatible with HMAC.", ex);
        }

        if (isTriviallyPredictable(secret)) {
            // Mensagem genérica: nunca inclui o segredo.
            throw new IllegalStateException(
                    "jwt.secret is too predictable to be used as a signing key.");
        }

        if (expiration == null || expiration < MINIMUM_EXPIRATION_MS
                || expiration > MAXIMUM_EXPIRATION_MS) {
            throw new IllegalStateException("jwt.expiration must be between 1 minute and 7 days.");
        }

        if (isProduction(activeProfiles, productionFlag) && containsInsecureMarker(secret)) {
            throw new IllegalStateException(
                    "jwt.secret appears to be a development value and cannot be used in production.");
        }
    }

    private static boolean isProduction(String activeProfiles, String productionFlag) {
        if (productionFlag != null && !productionFlag.isBlank()) {
            String flag = productionFlag.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(flag)) {
                return true;
            }
            if ("false".equals(flag)) {
                return false;
            }
            throw new IllegalStateException("v5.security.production must be true or false.");
        }

        Set<String> profiles = normalizedProfiles(activeProfiles);
        if (profiles.stream().anyMatch(PRODUCTION_PROFILES::contains)) {
            return true;
        }
        if (profiles.stream().anyMatch(DEVELOPMENT_PROFILES::contains)) {
            return false;
        }
        // Fail-closed: sem profile reconhecido, aplica a validação de produção.
        return true;
    }

    private static Set<String> normalizedProfiles(String activeProfiles) {
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return Set.of();
        }
        Set<String> profiles = new LinkedHashSet<>();
        for (String profile : Arrays.asList(activeProfiles.split(","))) {
            String normalized = Normalizer.normalize(profile.trim().toLowerCase(Locale.ROOT),
                    Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            if (!normalized.isEmpty()) {
                profiles.add(normalized);
            }
        }
        return profiles;
    }

    private static boolean containsInsecureMarker(String secret) {
        String normalized = secret.toLowerCase(Locale.ROOT);
        for (String marker : INSECURE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detecta apenas padrões degenerados e determinísticos. Não calcula entropia
     * nem exige formato específico: um segredo aleatório comum passa em todas as
     * verificações abaixo.
     */
    private static boolean isTriviallyPredictable(String secret) {
        int[] codePoints = secret.codePoints().toArray();

        Map<Integer, Integer> frequencies = new HashMap<>();
        for (int codePoint : codePoints) {
            frequencies.merge(codePoint, 1, Integer::sum);
        }
        if (frequencies.size() < MINIMUM_DISTINCT_CODE_POINTS) {
            return true;
        }
        int mostFrequent = frequencies.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        if (mostFrequent > codePoints.length * MAXIMUM_SINGLE_CODE_POINT_RATIO) {
            return true;
        }

        // Bloco curto repetido (ex.: "9f4b".repeat(16)).
        for (int blockLength = 1;
             blockLength <= MAXIMUM_REPEATED_BLOCK_LENGTH && blockLength * 2 <= secret.length();
             blockLength++) {
            if (secret.length() % blockLength != 0) {
                continue;
            }
            String block = secret.substring(0, blockLength);
            if (block.repeat(secret.length() / blockLength).equals(secret)) {
                return true;
            }
        }

        return hasMonotonicRun(codePoints);
    }

    /** Sequências longas e crescentes/decrescentes, como "abcdefghij..." ou "9876543...". */
    private static boolean hasMonotonicRun(int[] codePoints) {
        for (int step : new int[]{1, -1}) {
            int run = 1;
            for (int i = 1; i < codePoints.length; i++) {
                if (codePoints[i] - codePoints[i - 1] == step) {
                    run++;
                    if (run >= MINIMUM_SEQUENCE_RUN) {
                        return true;
                    }
                } else {
                    run = 1;
                }
            }
        }
        return false;
    }
}
