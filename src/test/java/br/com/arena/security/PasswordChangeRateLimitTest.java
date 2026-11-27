package br.com.arena.security;

import br.com.arena.dto.usuario.AlterarSenhaDTO;
import br.com.arena.enumerate.Perfil;
import br.com.arena.exception.BusinessException;
import br.com.arena.exception.RateLimitSource;
import br.com.arena.exception.TooManyRequestsException;
import br.com.arena.model.Usuario;
import br.com.arena.repository.UsuarioRepository;
import br.com.arena.service.PasswordChangeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V5.9.2.1 — Testes determinísticos do rate limit atômico e concorrência para alteração de senha.
 * Cobre todos os 11 cenários das especificações doc84 e doc85.
 */
class PasswordChangeRateLimitTest {

    private static final String IP = "192.0.2.10";
    private static final String HASH = "hash-atual";

    private final UsuarioRepository repository = mock(UsuarioRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AtomicLong clock = new AtomicLong(1_000L);
    private ClientRateLimiter limiter;

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    private SecurityHardeningProperties properties(int passwordLimit, int maxConcurrent) {
        return new SecurityHardeningProperties(10, 5, passwordLimit, maxConcurrent, 60, false, "", 5, 300, 20, 60);
    }

    private PasswordChangeService service(int passwordLimit, int maxConcurrent) {
        limiter = new ClientRateLimiter(properties(passwordLimit, maxConcurrent), clock::get);
        return new PasswordChangeService(repository, encoder, limiter);
    }

    private PasswordChangeService service(int passwordLimit) {
        return service(passwordLimit, 5);
    }

    private static void autenticar(long id, String email) {
        Usuario usuario = new Usuario(id, "Usuario", email, "11999999999", HASH,
                Perfil.CLIENTE, 0L, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, usuario.getAuthorities()));
    }

    private static AlterarSenhaDTO dto(String atual) {
        return AlterarSenhaDTO.builder()
                .senhaAtual(atual).novaSenha("novaSenha123").confirmacaoNovaSenha("novaSenha123")
                .build();
    }

    private static AlterarSenhaDTO dto(String atual, String nova, String confirmacao) {
        return AlterarSenhaDTO.builder()
                .senhaAtual(atual).novaSenha(nova).confirmacaoNovaSenha(confirmacao)
                .build();
    }

    // =========================================================================
    // Teste 1: Cinco operações simultâneas cobrindo o ciclo completo
    // =========================================================================
    @Test
    void teste1_cincoOperacoesSimultaneasNoCicloCompleto() throws Exception {
        int maxConcurrent = 5;
        PasswordChangeService servico = service(5, maxConcurrent);
        long accountId = 101L;
        autenticar(accountId, "user101@exemplo.invalid");

        CountDownLatch entraramMatchesAtual = new CountDownLatch(maxConcurrent);
        CountDownLatch liberarMatchesAtual = new CountDownLatch(1);

        CountDownLatch entraramMatchesNova = new CountDownLatch(maxConcurrent);
        CountDownLatch liberarMatchesNova = new CountDownLatch(1);

        CountDownLatch entraramEncode = new CountDownLatch(maxConcurrent);
        CountDownLatch liberarEncode = new CountDownLatch(1);

        when(encoder.matches(eq("atualCorreta"), eq(HASH))).thenAnswer(inv -> {
            entraramMatchesAtual.countDown();
            liberarMatchesAtual.await(10, TimeUnit.SECONDS);
            return true;
        });

        when(encoder.matches(eq("novaSenha123"), eq(HASH))).thenAnswer(inv -> {
            entraramMatchesNova.countDown();
            liberarMatchesNova.await(10, TimeUnit.SECONDS);
            return false;
        });

        when(encoder.encode(eq("novaSenha123"))).thenAnswer(inv -> {
            entraramEncode.countDown();
            liberarEncode.await(10, TimeUnit.SECONDS);
            return "novo-hash";
        });

        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrent);
        List<Future<?>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < maxConcurrent; i++) {
                final String ip = "192.0.2." + (i + 1);
                futuros.add(executor.submit(() -> {
                    autenticar(accountId, "user101@exemplo.invalid");
                    servico.alterarSenha(dto("atualCorreta"), ip);
                    return null;
                }));
            }

            assertTrue(entraramMatchesAtual.await(5, TimeUnit.SECONDS), "Todas devem entrar no 1º matches");
            assertEquals(5, limiter.passwordInFlightCount(accountId), "5 vagas em voo no 1º matches");
            liberarMatchesAtual.countDown();

            assertTrue(entraramMatchesNova.await(5, TimeUnit.SECONDS), "Todas devem entrar no 2º matches");
            assertEquals(5, limiter.passwordInFlightCount(accountId), "5 vagas continuam em voo no 2º matches");
            liberarMatchesNova.countDown();

            assertTrue(entraramEncode.await(5, TimeUnit.SECONDS), "Todas devem entrar no encode");
            assertEquals(5, limiter.passwordInFlightCount(accountId), "5 vagas continuam em voo no encode");
            liberarEncode.countDown();

            for (Future<?> f : futuros) {
                f.get(5, TimeUnit.SECONDS);
            }

            assertEquals(0, limiter.passwordInFlightCount(accountId), "Vagas zeradas após conclusão");
            verify(encoder, times(5)).matches(eq("atualCorreta"), eq(HASH));
            verify(encoder, times(5)).matches(eq("novaSenha123"), eq(HASH));
            verify(encoder, times(5)).encode(eq("novaSenha123"));
            verify(repository, times(5)).alterarSenha(eq(accountId), eq(HASH), eq("novo-hash"));
        } finally {
            executor.shutdownNow();
        }
    }

    // =========================================================================
    // Teste 2: Sexta operação rejeitada antes de qualquer BCrypt
    // =========================================================================
    @Test
    void teste2_sextaOperacaoRejeitadaAntesDeQualquerBCrypt() throws Exception {
        int maxConcurrent = 5;
        PasswordChangeService servico = service(5, maxConcurrent);
        long accountId = 102L;
        autenticar(accountId, "user102@exemplo.invalid");

        CountDownLatch cincoBloqueadas = new CountDownLatch(maxConcurrent);
        CountDownLatch liberar = new CountDownLatch(1);

        when(encoder.matches(eq("atualCorreta"), eq(HASH))).thenAnswer(inv -> {
            cincoBloqueadas.countDown();
            liberar.await(10, TimeUnit.SECONDS);
            return true;
        });
        when(encoder.matches(eq("novaSenha123"), eq(HASH))).thenReturn(false);
        when(encoder.encode(eq("novaSenha123"))).thenReturn("novo-hash");

        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<?>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < maxConcurrent; i++) {
                final String ip = "192.0.2." + (i + 1);
                futuros.add(executor.submit(() -> {
                    autenticar(accountId, "user102@exemplo.invalid");
                    servico.alterarSenha(dto("atualCorreta"), ip);
                    return null;
                }));
            }

            assertTrue(cincoBloqueadas.await(5, TimeUnit.SECONDS));
            assertEquals(5, limiter.passwordInFlightCount(accountId));

            // A 6ª operação é submetida de um 6º IP diferente:
            autenticar(accountId, "user102@exemplo.invalid");
            TooManyRequestsException ex = assertThrows(TooManyRequestsException.class,
                    () -> servico.alterarSenha(dto("atualCorreta"), "192.0.2.6"));

            assertNotNull(ex.getMessage());
            // Contadores de encoder devem permanecer estritamente em 5:
            verify(encoder, times(5)).matches(eq("atualCorreta"), eq(HASH));

            liberar.countDown();
            for (Future<?> f : futuros) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(0, limiter.passwordInFlightCount(accountId));
        } finally {
            executor.shutdownNow();
        }
    }

    // =========================================================================
    // Teste 3: Falhas independentes por IP (Correção do Achado A1)
    // =========================================================================
    @Test
    void teste3_falhasIndependentesPorIp() {
        PasswordChangeService servico = service(5, 5);
        long accountId = 103L;
        autenticar(accountId, "user103@exemplo.invalid");
        when(encoder.matches("errada", HASH)).thenReturn(false);

        String ipA = "192.0.2.1";
        String ipB = "192.0.2.2";

        // IP A erra 5 vezes sequencialmente
        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), ipA));
        }
        assertEquals(5, limiter.passwordFailureCount(accountId, ipA), "IP A deve ter exatamente 5 falhas");

        // 6ª requisição do IP A é rejeitada na admissão com 429
        assertThrows(TooManyRequestsException.class, () -> servico.alterarSenha(dto("errada"), ipA));

        // IP B tenta para a mesma conta e é admitido normalmente (isolamento por IP)
        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), ipB));
        assertEquals(1, limiter.passwordFailureCount(accountId, ipB), "IP B deve ter 1 falha");

        // IP B ainda pode tentar novamente (2ª tentativa admitida)
        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), ipB));
        assertEquals(2, limiter.passwordFailureCount(accountId, ipB));

        // IP A continua bloqueado
        assertThrows(TooManyRequestsException.class, () -> servico.alterarSenha(dto("errada"), ipA));
    }

    // =========================================================================
    // Teste 4: Idempotência de conclusão duplicada
    // =========================================================================
    @Test
    void teste4_conclusaoDuplicadaIdempotente() {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(5, 5), clock::get);
        long accountId = 104L;
        String ip = "192.0.2.4";

        UUID opId = rateLimiter.admitPasswordChange(accountId, ip);
        assertNotNull(opId);
        assertEquals(1, rateLimiter.passwordInFlightCount(accountId));

        // Primeira conclusão com falha
        rateLimiter.completePasswordChange(accountId, opId, Outcome.FAILED);
        assertEquals(0, rateLimiter.passwordInFlightCount(accountId));
        assertEquals(1, rateLimiter.passwordFailureCount(accountId, ip));

        // Segunda conclusão com mesmo opId (duplicada): no-op
        rateLimiter.completePasswordChange(accountId, opId, Outcome.FAILED);
        assertEquals(0, rateLimiter.passwordInFlightCount(accountId));
        assertEquals(1, rateLimiter.passwordFailureCount(accountId, ip));
    }

    // =========================================================================
    // Teste 5: Remoção e recriação de estado concorrente (múltiplas iterações)
    // =========================================================================
    @Test
    void teste5_remocaoERecriacaoDeEstadoConcorrente() throws Exception {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(5, 5), clock::get);
        long accountId = 105L;
        String ip = "192.0.2.5";

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteracao = 0; iteracao < 50; iteracao++) {
                UUID op1 = rateLimiter.admitPasswordChange(accountId, ip);
                assertNotNull(op1);
                assertEquals(1, rateLimiter.trackedPasswordStates());

                CountDownLatch latchInicio = new CountDownLatch(1);

                // T1 conclui com sucesso (esvazia e remove estado)
                Future<?> futuroConclusao = executor.submit(() -> {
                    latchInicio.await();
                    rateLimiter.completePasswordChange(accountId, op1, Outcome.SUCCESS);
                    return null;
                });

                // T2 admite simultaneamente nova operação
                Future<UUID> futuroAdmissao = executor.submit(() -> {
                    latchInicio.await();
                    return rateLimiter.admitPasswordChange(accountId, ip);
                });

                latchInicio.countDown();
                futuroConclusao.get(5, TimeUnit.SECONDS);
                UUID op2 = futuroAdmissao.get(5, TimeUnit.SECONDS);

                assertNotNull(op2);
                assertEquals(1, rateLimiter.trackedPasswordStates());
                assertEquals(1, rateLimiter.passwordInFlightCount(accountId));

                rateLimiter.completePasswordChange(accountId, op2, Outcome.SUCCESS);
                assertEquals(0, rateLimiter.trackedPasswordStates());
                assertEquals(0, rateLimiter.passwordInFlightCount(accountId));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // =========================================================================
    // Teste 6: Exceção em cada etapa de BCrypt libera a vaga sem registrar falha
    // =========================================================================
    @Test
    void teste6_excecaoEmCadaEtapaBCryptLiberaVaga() {
        PasswordChangeService servico = service(5, 5);
        long accountId = 106L;
        autenticar(accountId, "user106@exemplo.invalid");

        // Etapa 1: Exceção no 1º matches (senha atual)
        when(encoder.matches(eq("erro1"), eq(HASH))).thenThrow(new IllegalArgumentException("formato invalido"));
        assertThrows(IllegalArgumentException.class, () -> servico.alterarSenha(dto("erro1"), IP));
        assertEquals(0, limiter.passwordInFlightCount(accountId));
        assertEquals(0, limiter.passwordFailureCount(accountId, IP));

        // Etapa 2: Exceção no 2º matches (nova senha)
        when(encoder.matches(eq("valida"), eq(HASH))).thenReturn(true);
        when(encoder.matches(eq("novaSenha123"), eq(HASH))).thenThrow(new RuntimeException("falha matches reuso"));
        assertThrows(RuntimeException.class, () -> servico.alterarSenha(dto("valida"), IP));
        assertEquals(0, limiter.passwordInFlightCount(accountId));
        assertEquals(0, limiter.passwordFailureCount(accountId, IP));

        // Etapa 3: Exceção no encode (geração de hash)
        when(encoder.matches(eq("novaSenha123"), eq(HASH))).thenReturn(false);
        when(encoder.encode(eq("novaSenha123"))).thenThrow(new RuntimeException("falha encode"));
        assertThrows(RuntimeException.class, () -> servico.alterarSenha(dto("valida"), IP));
        assertEquals(0, limiter.passwordInFlightCount(accountId));
        assertEquals(0, limiter.passwordFailureCount(accountId, IP));

        // Etapa 4: Exceção no banco de dados
        when(encoder.encode(eq("novaSenha123"))).thenReturn("novo-hash");
        doThrow(new DataAccessException("banco fora") {}).when(repository).alterarSenha(any(), any(), any());
        assertThrows(DataAccessException.class, () -> servico.alterarSenha(dto("valida"), IP));
        assertEquals(0, limiter.passwordInFlightCount(accountId));
        assertEquals(0, limiter.passwordFailureCount(accountId, IP));
    }

    // =========================================================================
    // Teste 7: Falha contabilizada com timestamp atual após avanço de relógio
    // =========================================================================
    @Test
    void teste7_falhaContabilizadaAposAvancoDeRelogio() {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(5, 5), clock::get);
        long accountId = 107L;
        String ip = "192.0.2.7";

        UUID opId = rateLimiter.admitPasswordChange(accountId, ip);
        assertNotNull(opId);
        assertEquals(1, rateLimiter.passwordInFlightCount(accountId));

        // Avança o relógio além da janela de 60s (70 segundos)
        clock.addAndGet(70_000L);

        // Operação conclui agora como FAILED: timestamp registrado deve ser o do término
        rateLimiter.completePasswordChange(accountId, opId, Outcome.FAILED);
        assertEquals(0, rateLimiter.passwordInFlightCount(accountId));
        assertEquals(1, rateLimiter.passwordFailureCount(accountId, ip), "Falha não é perdida por expiração de lease");

        // Avança mais 59s: falha ainda está ativa
        clock.addAndGet(59_000L);
        assertEquals(1, rateLimiter.passwordFailureCount(accountId, ip));

        // Avança mais 2s (total 61s após conclusão): agora expira
        clock.addAndGet(2_000L);
        assertEquals(0, rateLimiter.passwordFailureCount(accountId, ip));
    }

    // =========================================================================
    // Teste 8: Thread interrompida não causa liberação prematura de vaga
    // =========================================================================
    @Test
    void teste8_threadInterrompidaNaoLiberaPrematuramente() throws Exception {
        int maxConcurrent = 1;
        PasswordChangeService servico = service(5, maxConcurrent);
        long accountId = 108L;
        autenticar(accountId, "user108@exemplo.invalid");

        CountDownLatch emExecucao = new CountDownLatch(1);
        CountDownLatch interrupcaoCapturada = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        AtomicBoolean foiInterrompida = new AtomicBoolean();

        when(encoder.matches(eq("atualCorreta"), eq(HASH))).thenAnswer(inv -> {
            emExecucao.countDown();
            boolean interrompida = false;
            while (liberar.getCount() > 0) {
                try {
                    liberar.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrompida = true;
                    foiInterrompida.set(true);
                    interrupcaoCapturada.countDown();
                }
            }
            if (interrompida) {
                // Restaura a flag de interrupção antes de prosseguir para persistência e finally.
                Thread.currentThread().interrupt();
            }
            return true;
        });
        when(encoder.matches(eq("novaSenha123"), eq(HASH))).thenReturn(false);
        when(encoder.encode(eq("novaSenha123"))).thenReturn("novo-hash");

        FutureTask<Boolean> task = new FutureTask<>(() -> {
            autenticar(accountId, "user108@exemplo.invalid");
            servico.alterarSenha(dto("atualCorreta"), IP);
            return Thread.currentThread().isInterrupted();
        });
        Thread t1 = new Thread(task, "teste8-interrupcao");
        t1.start();

        assertTrue(emExecucao.await(5, TimeUnit.SECONDS));
        assertEquals(1, limiter.passwordInFlightCount(accountId));

        // Sinaliza interrupção em t1 enquanto bloqueada em matches
        t1.interrupt();
        assertTrue(interrupcaoCapturada.await(5, TimeUnit.SECONDS), "A interrupção deve ser efetivamente capturada em t1");
        assertTrue(foiInterrompida.get(), "Flag de interrupção observada deve ser verdadeira");

        // Enquanto t1 não saiu do encoder, a vaga deve continuar ocupada:
        assertEquals(1, limiter.passwordInFlightCount(accountId));
        assertThrows(TooManyRequestsException.class, () -> servico.alterarSenha(dto("atualCorreta"), "192.0.2.99"));

        // Libera para t1 concluir persistência e liberação no finally com estado interrompido restaurado
        liberar.countDown();
        boolean statusInterrompidoPreservado = task.get(5, TimeUnit.SECONDS);
        t1.join(5000);

        assertTrue(statusInterrompidoPreservado, "Estado de interrupção deve permanecer preservado após conclusão");
        assertEquals(0, limiter.passwordInFlightCount(accountId));
        assertEquals(0, limiter.passwordFailureCount(accountId, IP));
        verify(repository).alterarSenha(accountId, HASH, "novo-hash");
    }

    // =========================================================================
    // Teste 9: Limpeza periódica passiva (maybeSweep) e isolamento de falhas
    // =========================================================================
    @Test
    void teste9_limpezaPassivaPeriodica() {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(5, 5), clock::get);
        long contaAtiva = 1091L;
        long contaInativa = 1092L;

        // Conta ativa tem operação em andamento
        UUID opAtiva = rateLimiter.admitPasswordChange(contaAtiva, "192.0.2.1");
        assertNotNull(opAtiva);

        // Conta inativa teve falha
        UUID opInativa = rateLimiter.admitPasswordChange(contaInativa, "192.0.2.2");
        rateLimiter.completePasswordChange(contaInativa, opInativa, Outcome.FAILED);

        assertEquals(2, rateLimiter.trackedPasswordStates());

        // Avança relógio além de 60 segundos
        clock.addAndGet(61_000L);

        // Nova admissão aciona maybeSweep
        UUID opNova = rateLimiter.admitPasswordChange(9999L, "192.0.2.3");
        assertNotNull(opNova);

        // Valida primeiro trackedPasswordStates antes de consultar passwordFailureCount,
        // garantindo que a remoção da conta inativa foi feita pelo sweep e não pelo acessor.
        assertEquals(2, rateLimiter.trackedPasswordStates(), "Apenas contaAtiva e conta 9999L permanecem após o sweep");
        assertEquals(1, rateLimiter.passwordInFlightCount(contaAtiva));
        assertEquals(0, rateLimiter.passwordFailureCount(contaInativa, "192.0.2.2"));

        rateLimiter.completePasswordChange(contaAtiva, opAtiva, Outcome.SUCCESS);
        rateLimiter.completePasswordChange(9999L, opNova, Outcome.SUCCESS);
        assertEquals(0, rateLimiter.trackedPasswordStates());
    }

    @Test
    void teste9b_falhaRecuperavelNoSweepNaoComprometeAdmissao() {
        ClientRateLimiter rateLimiterSpy = spy(new ClientRateLimiter(properties(5, 5), clock::get));
        long accountId = 1093L;

        // Avança o relógio além da janela para que a admissão acione o sweep
        clock.addAndGet(61_000L);
        doThrow(new RuntimeException("falha recuperável simulada no sweep"))
                .when(rateLimiterSpy).sweepPasswordStates(anyLong(), anyLong());

        UUID opId = rateLimiterSpy.admitPasswordChange(accountId, IP);
        assertNotNull(opId, "Falha recuperável no sweep não pode impedir a admissão legítima");
        assertEquals(1, rateLimiterSpy.passwordInFlightCount(accountId));
        verify(rateLimiterSpy).sweepPasswordStates(anyLong(), anyLong());

        rateLimiterSpy.completePasswordChange(accountId, opId, Outcome.SUCCESS);
        assertEquals(0, rateLimiterSpy.trackedPasswordStates());

        // Erro grave da JVM durante o sweep não deve ser capturado
        clock.addAndGet(61_000L);
        doThrow(new OutOfMemoryError("oom simulado no sweep"))
                .when(rateLimiterSpy).sweepPasswordStates(anyLong(), anyLong());
        assertThrows(OutOfMemoryError.class, () -> rateLimiterSpy.admitPasswordChange(accountId, IP));
    }

    // =========================================================================
    // Teste 10: Falha inesperada e Error grave da JVM durante complete (I1 / A3)
    // =========================================================================
    @Test
    void teste10_falhaInesperadaDuranteComplete() {
        ClientRateLimiter rateLimiterSpy = spy(new ClientRateLimiter(properties(5, 5), clock::get));
        PasswordChangeService servico = new PasswordChangeService(repository, encoder, rateLimiterSpy);
        long accountId = 110L;
        autenticar(accountId, "user110@exemplo.invalid");

        // Cenário A: Serviço tem sucesso, mas completePasswordChange falha -> lança IllegalStateException
        when(encoder.matches(eq("correta"), eq(HASH))).thenReturn(true);
        when(encoder.matches(eq("novaSenha123"), eq(HASH))).thenReturn(false);
        when(encoder.encode(eq("novaSenha123"))).thenReturn("novo-hash");
        doThrow(new RuntimeException("falha simulada em complete")).when(rateLimiterSpy)
                .completePasswordChange(eq(accountId), any(), eq(Outcome.SUCCESS));

        IllegalStateException exSucesso = assertThrows(IllegalStateException.class,
                () -> servico.alterarSenha(dto("correta"), IP));
        assertTrue(exSucesso.getMessage().contains("Falha de consistência interna"));

        // Cenário B: Serviço tem erro de negócio (400), e completePasswordChange falha -> 400 preservado com erro suprimido
        when(encoder.matches(eq("errada"), eq(HASH))).thenReturn(false);
        doThrow(new RuntimeException("falha simulada em complete")).when(rateLimiterSpy)
                .completePasswordChange(eq(accountId), any(), eq(Outcome.FAILED));

        BusinessException exNegocio = assertThrows(BusinessException.class,
                () -> servico.alterarSenha(dto("errada"), IP));
        assertEquals("Senha atual inválida.", exNegocio.getMessage());
        assertEquals(1, exNegocio.getSuppressed().length, "Exceção do complete deve ser anexada como suprimida");
        assertEquals("falha simulada em complete", exNegocio.getSuppressed()[0].getMessage());
    }

    @Test
    void teste10b_erroGraveJvmDuranteCompletePropagaSemConverterEmIllegalStateException() {
        ClientRateLimiter rateLimiterSpy = spy(new ClientRateLimiter(properties(5, 5), clock::get));
        PasswordChangeService servico = new PasswordChangeService(repository, encoder, rateLimiterSpy);
        long accountId = 1101L;
        autenticar(accountId, "user1101@exemplo.invalid");

        // Cenário A: Caminho feliz no serviço + OutOfMemoryError no complete -> propaga OutOfMemoryError diretamente
        when(encoder.matches(eq("correta"), eq(HASH))).thenReturn(true);
        when(encoder.matches(eq("novaSenha123"), eq(HASH))).thenReturn(false);
        when(encoder.encode(eq("novaSenha123"))).thenReturn("novo-hash");
        doThrow(new OutOfMemoryError("oom simulado em complete")).when(rateLimiterSpy)
                .completePasswordChange(eq(accountId), any(), eq(Outcome.SUCCESS));

        OutOfMemoryError erroCaminhoFeliz = assertThrows(OutOfMemoryError.class,
                () -> servico.alterarSenha(dto("correta"), IP));
        assertEquals("oom simulado em complete", erroCaminhoFeliz.getMessage());

        // Cenário B: Erro de negócio no try + OutOfMemoryError no complete -> propaga Error da JVM sem mascarar
        when(encoder.matches(eq("errada"), eq(HASH))).thenReturn(false);
        doThrow(new OutOfMemoryError("oom simulado em complete falha")).when(rateLimiterSpy)
                .completePasswordChange(eq(accountId), any(), eq(Outcome.FAILED));

        OutOfMemoryError erroCaminhoNegocio = assertThrows(OutOfMemoryError.class,
                () -> servico.alterarSenha(dto("errada"), IP));
        assertEquals("oom simulado em complete falha", erroCaminhoNegocio.getMessage());

        // Cenário C: OutOfMemoryError dentro do ciclo BCrypt -> finally libera vaga com Outcome.INFRA e propaga Error
        ClientRateLimiter limiterReal = new ClientRateLimiter(properties(5, 5), clock::get);
        PasswordChangeService servicoReal = new PasswordChangeService(repository, encoder, limiterReal);
        when(encoder.matches(eq("boom"), eq(HASH))).thenThrow(new OutOfMemoryError("oom no bcrypt"));

        OutOfMemoryError erroBcrypt = assertThrows(OutOfMemoryError.class,
                () -> servicoReal.alterarSenha(dto("boom"), IP));
        assertEquals("oom no bcrypt", erroBcrypt.getMessage());
        assertEquals(0, limiterReal.passwordInFlightCount(accountId));
        assertEquals(0, limiterReal.passwordFailureCount(accountId, IP));
    }

    // =========================================================================
    // Teste 11: Negação de admissão preserva estado ativo (Achado A5 / I4)
    // =========================================================================
    @Test
    void teste11_negacaoDeAdmissaoPreservaEstadoAtivo() {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(5, 2), clock::get);
        long accountId = 111L;

        UUID op1 = rateLimiter.admitPasswordChange(accountId, "192.0.2.1");
        UUID op2 = rateLimiter.admitPasswordChange(accountId, "192.0.2.2");
        assertNotNull(op1);
        assertNotNull(op2);
        assertEquals(2, rateLimiter.passwordInFlightCount(accountId));
        assertEquals(1, rateLimiter.trackedPasswordStates());

        // 3ª tentativa é negada (limite é 2)
        UUID op3 = rateLimiter.admitPasswordChange(accountId, "192.0.2.3");
        assertNull(op3, "3ª admissão deve ser negada");

        // O estado ativo NÃO PODE ter sido destruído nem removido do ConcurrentHashMap:
        assertEquals(1, rateLimiter.trackedPasswordStates(), "O nó deve continuar existindo no mapa");
        assertEquals(2, rateLimiter.passwordInFlightCount(accountId), "As 2 operações ativas continuam intactas");

        // Conclui op1 e op2
        rateLimiter.completePasswordChange(accountId, op1, Outcome.SUCCESS);
        assertEquals(1, rateLimiter.passwordInFlightCount(accountId));

        rateLimiter.completePasswordChange(accountId, op2, Outcome.SUCCESS);
        assertEquals(0, rateLimiter.passwordInFlightCount(accountId));
        assertEquals(0, rateLimiter.trackedPasswordStates());
    }

    @Test
    void teste11b_negacaoPorLimiteDeFalhasPreservaOperacaoAtivaDeOutroIp() {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(2, 5), clock::get);
        long accountId = 112L;
        String ipBloqueado = "192.0.2.10";
        String ipAtivo = "192.0.2.20";

        // IP A acumula 2 falhas e atinge o limite de falhas
        for (int i = 0; i < 2; i++) {
            UUID opFalha = rateLimiter.admitPasswordChange(accountId, ipBloqueado);
            assertNotNull(opFalha);
            rateLimiter.completePasswordChange(accountId, opFalha, Outcome.FAILED);
        }
        assertEquals(2, rateLimiter.passwordFailureCount(accountId, ipBloqueado));

        // IP B inicia uma operação ativa na mesma conta
        UUID opAtivaB = rateLimiter.admitPasswordChange(accountId, ipAtivo);
        assertNotNull(opAtivaB);
        assertEquals(1, rateLimiter.passwordInFlightCount(accountId));

        // Nova tentativa do IP A é negada pelo failureLimit (não por maxConcurrent)
        UUID opNegadaA = rateLimiter.admitPasswordChange(accountId, ipBloqueado);
        assertNull(opNegadaA, "IP A deve ser negado por limite de falhas");

        // Estado ativo da conta (operação do IP B e falhas do IP A) permanece preservado
        assertEquals(1, rateLimiter.trackedPasswordStates());
        assertEquals(1, rateLimiter.passwordInFlightCount(accountId));
        assertEquals(2, rateLimiter.passwordFailureCount(accountId, ipBloqueado));

        // IP B continua operando normalmente enquanto IP A está bloqueado
        UUID opAtivaB2 = rateLimiter.admitPasswordChange(accountId, ipAtivo);
        assertNotNull(opAtivaB2);
        assertEquals(2, rateLimiter.passwordInFlightCount(accountId));

        rateLimiter.completePasswordChange(accountId, opAtivaB, Outcome.SUCCESS);
        rateLimiter.completePasswordChange(accountId, opAtivaB2, Outcome.SUCCESS);
        assertEquals(0, rateLimiter.passwordInFlightCount(accountId));
        assertEquals(2, rateLimiter.passwordFailureCount(accountId, ipBloqueado));
        assertEquals(1, rateLimiter.trackedPasswordStates());
    }

    // =========================================================================
    // Testes de regressão da V5.9.2
    // =========================================================================
    @Test
    void senhaIncorretaConsomeAteOLimiteEDepoisBloqueia() {
        PasswordChangeService servico = service(2, 5);
        autenticar(1L, "usuario1@exemplo.invalid");
        when(encoder.matches("errada", HASH)).thenReturn(false);

        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));
        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));

        TooManyRequestsException erro = assertThrows(TooManyRequestsException.class,
                () -> servico.alterarSenha(dto("errada"), IP));
        assertFalse(erro.getMessage().contains("errada"));
        verify(repository, never()).alterarSenha(any(), any(), any());
    }

    @Test
    void contaBloqueadaNaoExecutaAlteracaoMesmoComSenhaCorreta() {
        PasswordChangeService servico = service(1, 5);
        autenticar(2L, "usuario2@exemplo.invalid");
        when(encoder.matches("errada", HASH)).thenReturn(false);
        when(encoder.matches("correta123", HASH)).thenReturn(true);

        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));

        TooManyRequestsException erro = assertThrows(TooManyRequestsException.class,
                () -> servico.alterarSenha(dto("correta123"), IP));
        assertFalse(erro.getMessage().contains("correta123"));
        verify(encoder, never()).encode(any());
        verify(repository, never()).alterarSenha(any(), any(), any());
    }

    @Test
    void senhaCorretaNaoConsomeTentativa() {
        PasswordChangeService servico = service(5, 5);
        autenticar(3L, "usuario3@exemplo.invalid");
        when(encoder.matches("errada", HASH)).thenReturn(false);
        when(encoder.matches("correta123", HASH)).thenReturn(true);
        when(encoder.matches("novaSenha123", HASH)).thenReturn(false);
        when(encoder.encode("novaSenha123")).thenReturn("novo-hash");

        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));
        servico.alterarSenha(dto("correta123"), IP);

        verify(repository).alterarSenha(3L, HASH, "novo-hash");
        assertEquals(1, limiter.passwordFailureCount(3L, IP));
    }

    @Test
    void falhaDeInfraestruturaNaoConsomeTentativa() {
        PasswordChangeService servico = service(5, 5);
        autenticar(4L, "usuario4@exemplo.invalid");
        when(encoder.matches("correta123", HASH)).thenReturn(true);
        when(encoder.matches("novaSenha123", HASH)).thenReturn(false);
        when(encoder.encode("novaSenha123")).thenReturn("novo-hash");
        when(repository.alterarSenha(any(), any(), any()))
                .thenThrow(new DataAccessException("indisponivel") {});

        assertThrows(DataAccessException.class,
                () -> servico.alterarSenha(dto("correta123"), IP));

        assertEquals(0, limiter.passwordFailureCount(4L, IP));
    }

    @Test
    void janelaExpiradaPermiteNovasTentativas() {
        PasswordChangeService servico = service(1, 5);
        autenticar(5L, "usuario5@exemplo.invalid");
        when(encoder.matches("errada", HASH)).thenReturn(false);
        when(encoder.matches("correta123", HASH)).thenReturn(true);
        when(encoder.matches("novaSenha123", HASH)).thenReturn(false);
        when(encoder.encode("novaSenha123")).thenReturn("novo-hash");

        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));
        assertThrows(TooManyRequestsException.class,
                () -> servico.alterarSenha(dto("correta123"), IP));

        clock.addAndGet(60_001L);
        servico.alterarSenha(dto("correta123"), IP);

        verify(repository).alterarSenha(5L, HASH, "novo-hash");
    }

    @Test
    void contasEIpDiferentesNaoCompartilhamLimite() {
        PasswordChangeService servico = service(1, 5);
        when(encoder.matches("errada", HASH)).thenReturn(false);

        autenticar(6L, "usuario6@exemplo.invalid");
        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));

        autenticar(7L, "usuario7@exemplo.invalid");
        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));

        autenticar(6L, "usuario6@exemplo.invalid");
        assertThrows(BusinessException.class,
                () -> servico.alterarSenha(dto("errada"), "192.0.2.99"));
    }

    @Test
    void requisicoesConcorrentesNaoUltrapassamOLimite() throws Exception {
        PasswordChangeService servico = service(5, 5);
        long accountId = 8L;
        autenticar(accountId, "usuario8@exemplo.invalid");

        int totalThreads = 8;
        CountDownLatch inicio = new CountDownLatch(1);
        CountDownLatch terminaramAdmissao = new CountDownLatch(totalThreads);
        CountDownLatch liberar = new CountDownLatch(1);

        when(encoder.matches(eq("errada"), eq(HASH))).thenAnswer(inv -> {
            terminaramAdmissao.countDown();
            liberar.await(10, TimeUnit.SECONDS);
            return false;
        });

        AtomicInteger falhasNegocio = new AtomicInteger();
        AtomicInteger bloqueadas = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        List<Future<?>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < totalThreads; i++) {
                futuros.add(executor.submit(() -> {
                    autenticar(accountId, "usuario8@exemplo.invalid");
                    inicio.await();
                    try {
                        servico.alterarSenha(dto("errada"), IP);
                    } catch (BusinessException ex) {
                        falhasNegocio.incrementAndGet();
                    } catch (TooManyRequestsException ex) {
                        bloqueadas.incrementAndGet();
                        terminaramAdmissao.countDown();
                    }
                    return null;
                }));
            }

            inicio.countDown();
            assertTrue(terminaramAdmissao.await(5, TimeUnit.SECONDS));
            assertEquals(5, limiter.passwordInFlightCount(accountId));
            assertEquals(3, bloqueadas.get());

            liberar.countDown();
            for (Future<?> futuro : futuros) {
                futuro.get(5, TimeUnit.SECONDS);
            }

            assertEquals(5, falhasNegocio.get(), "no máximo 5 requisições devem chegar ao BCrypt e falhar com 400");
            assertEquals(3, bloqueadas.get(), "as 3 excedentes devem ser rejeitadas com 429");
            assertEquals(0, limiter.passwordInFlightCount(accountId));
            assertEquals(5, limiter.passwordFailureCount(accountId, IP));
            verify(encoder, times(5)).matches(eq("errada"), eq(HASH));
            verify(repository, never()).alterarSenha(any(), any(), any());

            autenticar(accountId, "usuario8@exemplo.invalid");
            assertThrows(TooManyRequestsException.class, () -> servico.alterarSenha(dto("errada"), IP));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void normalizacaoDeIpAgrupaVariacoesEquivalentesEReservaUnknown() {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(5, 5), clock::get);

        // Entradas nulas, em branco ou reduzidas a vazio pela normalização compartilham "unknown"
        long contaUnknown = 201L;
        for (String entrada : Arrays.asList(null, "", "   ", "[]", "%eth0")) {
            UUID op = rateLimiter.admitPasswordChange(contaUnknown, entrada);
            assertNotNull(op);
            rateLimiter.completePasswordChange(contaUnknown, op, Outcome.FAILED);
        }
        assertEquals(5, rateLimiter.passwordFailureCount(contaUnknown, null));
        assertEquals(5, rateLimiter.passwordFailureCount(contaUnknown, "unknown"));
        assertNull(rateLimiter.admitPasswordChange(contaUnknown, null),
                "null deve ser bloqueado pelo limite acumulado em 'unknown'");
        assertNull(rateLimiter.admitPasswordChange(contaUnknown, "   "),
                "string em branco deve ser bloqueada pelo limite acumulado em 'unknown'");

        // Espaços, maiúsculas, colchetes e zone ID de IPv6 convergem para a mesma chave
        long contaIpv6 = 202L;
        for (String entrada : List.of(" 2001:DB8::1 ", "[2001:db8::1]", "2001:DB8::1%eth0")) {
            UUID op = rateLimiter.admitPasswordChange(contaIpv6, entrada);
            assertNotNull(op);
            rateLimiter.completePasswordChange(contaIpv6, op, Outcome.FAILED);
        }
        assertEquals(3, rateLimiter.passwordFailureCount(contaIpv6, "2001:db8::1"));
        assertNotNull(rateLimiter.admitPasswordChange(contaIpv6, "2001:db8::1"));
        assertEquals(3, rateLimiter.passwordFailureCount(contaIpv6, " [2001:DB8::1%wlan0] "));

        ClientRateLimiter rateLimiterIpv6Limite3 = new ClientRateLimiter(properties(3, 5), clock::get);
        for (String entrada : List.of(" 2001:DB8::1 ", "[2001:db8::1]", "2001:DB8::1%eth0")) {
            UUID op = rateLimiterIpv6Limite3.admitPasswordChange(contaIpv6, entrada);
            assertNotNull(op);
            rateLimiterIpv6Limite3.completePasswordChange(contaIpv6, op, Outcome.FAILED);
        }
        assertNull(rateLimiterIpv6Limite3.admitPasswordChange(contaIpv6, " [2001:DB8::1%wlan0] "),
                "variações equivalentes normalizadas devem atingir o limite juntas");

        // IPv4 com espaços nas extremidades é normalizado
        long contaIpv4 = 203L;
        UUID opIpv4 = rateLimiter.admitPasswordChange(contaIpv4, " 192.0.2.50 ");
        assertNotNull(opIpv4);
        rateLimiter.completePasswordChange(contaIpv4, opIpv4, Outcome.FAILED);
        assertEquals(1, rateLimiter.passwordFailureCount(contaIpv4, "192.0.2.50"));
    }

    @Test
    void admitPasswordChangeWithDetailsDiferenciaConcorrenciaELimiteDeFalhasComRetryAfter() {
        ClientRateLimiter rateLimiter = new ClientRateLimiter(properties(2, 2), clock::get);
        long accountId = 301L;
        String ipA = "192.0.2.10";
        String ipB = "192.0.2.20";

        // 1. Satura concorrência (2 operações em voo) -> 3ª retorna PASSWORD_CHANGE_CONCURRENCY e Retry-After 1
        UUID op1 = rateLimiter.admitPasswordChange(accountId, ipA);
        UUID op2 = rateLimiter.admitPasswordChange(accountId, ipB);
        assertNotNull(op1);
        assertNotNull(op2);

        ClientRateLimiter.PasswordAdmissionResult negadoConcorrencia =
                rateLimiter.admitPasswordChangeWithDetails(accountId, "192.0.2.30");
        assertFalse(negadoConcorrencia.admitted());
        assertNull(negadoConcorrencia.operationId());
        assertEquals(RateLimitSource.PASSWORD_CHANGE_CONCURRENCY, negadoConcorrencia.rejectionSource());
        assertEquals(1L, negadoConcorrencia.retryAfterSeconds());

        // 2. Conclui ambas com FAILED no ipA -> atinge limite de 2 falhas para ipA
        rateLimiter.completePasswordChange(accountId, op1, Outcome.FAILED);
        rateLimiter.completePasswordChange(accountId, op2, Outcome.SUCCESS);

        UUID op3 = rateLimiter.admitPasswordChange(accountId, ipA);
        assertNotNull(op3);
        rateLimiter.completePasswordChange(accountId, op3, Outcome.FAILED);

        // Avança 15 s na janela de 60 s -> faltam 45 s
        clock.addAndGet(15_000L);
        ClientRateLimiter.PasswordAdmissionResult negadoFalhas =
                rateLimiter.admitPasswordChangeWithDetails(accountId, ipA);
        assertFalse(negadoFalhas.admitted());
        assertEquals(RateLimitSource.PASSWORD_CHANGE_RATE_LIMIT, negadoFalhas.rejectionSource());
        assertEquals(45L, negadoFalhas.retryAfterSeconds());

        // Último milissegundo da janela -> piso 1 s
        clock.set(1_000L + 60_000L);
        ClientRateLimiter.PasswordAdmissionResult negadoUltimoMs =
                rateLimiter.admitPasswordChangeWithDetails(accountId, ipA);
        assertFalse(negadoUltimoMs.admitted());
        assertEquals(RateLimitSource.PASSWORD_CHANGE_RATE_LIMIT, negadoUltimoMs.rejectionSource());
        assertEquals(1L, negadoUltimoMs.retryAfterSeconds());

        // Após expiração da janela, volta a admitir
        clock.set(1_000L + 60_001L);
        ClientRateLimiter.PasswordAdmissionResult readmitido =
                rateLimiter.admitPasswordChangeWithDetails(accountId, ipA);
        assertTrue(readmitido.admitted());
        assertNull(readmitido.rejectionSource());
        assertEquals(0L, readmitido.retryAfterSeconds());
        rateLimiter.completePasswordChange(accountId, readmitido.operationId(), Outcome.SUCCESS);
    }

    @Test
    void servicoPropagaOrigemERetryAfterNosDoisModosDeBloqueio429() throws Exception {
        PasswordChangeService servico = service(2, 1);
        long accountId = 302L;
        autenticar(accountId, "user302@exemplo.invalid");

        // Bloqueio por concorrência
        CountDownLatch emExecucao = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        when(encoder.matches(eq("atual"), eq(HASH))).thenAnswer(inv -> {
            emExecucao.countDown();
            liberar.await(5, TimeUnit.SECONDS);
            return false;
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> emVoo = executor.submit(() -> {
                autenticar(accountId, "user302@exemplo.invalid");
                assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("atual"), IP));
                return null;
            });
            assertTrue(emExecucao.await(5, TimeUnit.SECONDS));

            autenticar(accountId, "user302@exemplo.invalid");
            TooManyRequestsException erroConcorrencia = assertThrows(TooManyRequestsException.class,
                    () -> servico.alterarSenha(dto("atual"), "192.0.2.88"));
            assertEquals(RateLimitSource.PASSWORD_CHANGE_CONCURRENCY, erroConcorrencia.getSource());
            assertEquals(1L, erroConcorrencia.getRetryAfterSeconds());

            liberar.countDown();
            emVoo.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // Segunda falha atinge o limite de 2 falhas no IP
        when(encoder.matches(eq("errada"), eq(HASH))).thenReturn(false);
        autenticar(accountId, "user302@exemplo.invalid");
        assertThrows(BusinessException.class, () -> servico.alterarSenha(dto("errada"), IP));

        clock.addAndGet(20_000L); // 20 s após a 1ª falha -> faltam 40 s
        TooManyRequestsException erroFalhas = assertThrows(TooManyRequestsException.class,
                () -> servico.alterarSenha(dto("errada"), IP));
        assertEquals(RateLimitSource.PASSWORD_CHANGE_RATE_LIMIT, erroFalhas.getSource());
        assertEquals(40L, erroFalhas.getRetryAfterSeconds());
    }
}
