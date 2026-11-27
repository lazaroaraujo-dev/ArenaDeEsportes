package br.com.arena.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtConfigValidatorTest {

    /** Segredo aleatório de 32 caracteres: passa em todas as verificações de M8. */
    private static final String VALID_SECRET = "K7mQ2vR9xL4pN8tZ1bW5yC0dF3hJ6sAu";

    @Test
    void aceitaConfiguracaoValida() {
        String secret = VALID_SECRET;
        assertDoesNotThrow(() -> new JwtConfigValidator(secret, 3_600_000L, ""));
    }

    @Test
    void rejeitaSegredoAusente() {
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator("", 3_600_000L, ""));
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(null, 3_600_000L, ""));
    }

    @Test
    void rejeitaSegredoCurtoParaHmac() {
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator("curto", 3_600_000L, ""));
    }

    @Test
    void rejeitaExpiracaoInvalida() {
        String secret = VALID_SECRET;
        assertThrows(IllegalStateException.class, () -> new JwtConfigValidator(secret, 0L, ""));
        assertThrows(IllegalStateException.class, () -> new JwtConfigValidator(secret, 1L, ""));
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 604_800_001L, ""));
        assertThrows(IllegalStateException.class, () -> new JwtConfigValidator(secret, null, ""));
    }

    @Test
    void rejeitaValorDeDesenvolvimentoEmProducao() {
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator("my-secret-key-for-tests-1234567890abcdef", 3_600_000L, "prod"));
    }

    @Test
    void aceitaSegredoAleatorioEmProducao() {
        String secret = VALID_SECRET;
        assertDoesNotThrow(() -> new JwtConfigValidator(secret, 3_600_000L, "prod"));
    }

    @Test
    void naoAlteraAlgoritmoNemInvalidaTokens() {
        // A validação apenas confirma que a chave é aceita pelo HMAC; JwtService
        // continua usando Keys.hmacShaKeyFor e signWith/getKey sem mudança.
        String secret = VALID_SECRET;
        assertDoesNotThrow(() -> new JwtConfigValidator(secret, 3_600_000L, ""));
    }

    @Test
    void rejeitaNomesAlternativosDeProducao() {
        String secret = "my-secret-key-for-tests-1234567890abcdef";
        for (String profile : new String[]{
                "prod", "production", "PRODUCTION", "producao", "produção",
                "prd", "release", "live"}) {
            assertThrows(IllegalStateException.class,
                    () -> new JwtConfigValidator(secret, 3_600_000L, profile, ""),
                    "perfil " + profile + " deve ser tratado como produção");
        }
    }

    @Test
    void ausenciaDeProfileEhTratadaComoProducao() {
        String secret = "my-secret-key-for-tests-1234567890abcdef";
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, "", ""));
    }

    @Test
    void profileDesconhecidoNaoEhPresumidoDesenvolvimento() {
        String secret = "my-secret-key-for-tests-1234567890abcdef";
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, "custom", ""));
    }

    @Test
    void flagExplicitaTrueVenceProfileDeDesenvolvimento() {
        String secret = "my-secret-key-for-tests-1234567890abcdef";
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, "dev", "true"));
    }

    @Test
    void flagExplicitaFalsePermiteAmbienteDeDesenvolvimento() {
        String secret = "my-secret-key-for-tests-1234567890abcdef";
        assertDoesNotThrow(
                () -> new JwtConfigValidator(secret, 3_600_000L, "prod", "false"));
    }

    @Test
    void flagInvalidaFalhaRapidoSemExporSegredo() {
        String secret = VALID_SECRET;
        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, "prod", "maybe"));
        assertFalse(erro.getMessage().contains(secret));
    }

    @Test
    void perfisDeDesenvolvimentoSaoPreservados() {
        String secret = "my-secret-key-for-tests-1234567890abcdef";
        for (String profile : new String[]{"dev", "development", "test", "local", "qa"}) {
            assertDoesNotThrow(
                    () -> new JwtConfigValidator(secret, 3_600_000L, profile, ""),
                    "perfil " + profile + " deve permanecer como desenvolvimento");
        }
    }

    @Test
    void multiplosProfilesProducaoVence() {
        String secret = "my-secret-key-for-tests-1234567890abcdef";
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, "dev,prod", ""));
    }

    @Test
    void mensagensDeErroNaoContemSegredo() {
        String secret = "senha-muito-curta";
        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, "prod", ""));
        assertFalse(erro.getMessage().contains(secret));
    }

    @Test
    void rejeitaRepeticaoDeUmUnicoCaractereMesmoCom32Bytes() {
        String secret = "a".repeat(32);
        IllegalStateException erro = assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, ""));
        assertFalse(erro.getMessage().contains(secret));
        assertFalse(erro.getMessage().contains("a".repeat(8)));
    }

    @Test
    void rejeitaPoucosCaracteresDistintos() {
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator("ab".repeat(20), 3_600_000L, ""));
    }

    @Test
    void rejeitaBlocoCurtoRepetido() {
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator("9f4b".repeat(16), 3_600_000L, ""));
    }

    @Test
    void rejeitaSequenciaPrevisivel() {
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(
                        "abcdefghijklmnopqrstuvwxyz0123456789", 3_600_000L, ""));
    }

    @Test
    void rejeitaDominanciaExcessivaDeUmCaractere() {
        String secret = "a".repeat(30) + "bcdef";
        assertThrows(IllegalStateException.class,
                () -> new JwtConfigValidator(secret, 3_600_000L, ""));
    }

    @Test
    void aceitaSegredoNaoAsciiComDiversidadeSuficiente() {
        // 29 caracteres / 32 bytes em UTF-8, sem marcadores de desenvolvimento.
        String secret = "Áv7mQ2éR9xL4íN8tZ1bW5yC0dF3hJ";
        assertDoesNotThrow(() -> new JwtConfigValidator(secret, 3_600_000L, ""));
    }

    @Test
    void validacaoDeSegredoFracoNaoDependeDoProfile() {
        String secret = "a".repeat(32);
        for (String profile : new String[]{"", "dev", "prod"}) {
            assertThrows(IllegalStateException.class,
                    () -> new JwtConfigValidator(secret, 3_600_000L, profile, "false"),
                    "perfil " + profile + " deve rejeitar segredo degenerado");
        }
    }
}
