package br.com.arena.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRateLimiterTest {

    private static SecurityHardeningProperties properties() {
        return new SecurityHardeningProperties(2, 2, 60, false, 5, 300);
    }

    @Test
    void permiteRequisicoesDentroDoLimite() {
        AtomicLong clock = new AtomicLong(1_000L);
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), clock::get);

        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertFalse(limiter.tryAcquire("login", "ip-1"));
    }

    @Test
    void clientesDiferentesTemContadoresIndependentes() {
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), () -> 1_000L);

        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertFalse(limiter.tryAcquire("login", "ip-1"));
        assertTrue(limiter.tryAcquire("login", "ip-2"));
    }

    @Test
    void recuperaAposFimDaJanela() {
        AtomicLong clock = new AtomicLong(1_000L);
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), clock::get);

        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertFalse(limiter.tryAcquire("login", "ip-1"));

        clock.set(1_000L + 60_000L + 1L);
        assertTrue(limiter.tryAcquire("login", "ip-1"));
    }

    @Test
    void limitesPorCategoriaSaoIndependentes() {
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), () -> 1_000L);

        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertTrue(limiter.tryAcquire("login", "ip-1"));
        assertFalse(limiter.tryAcquire("login", "ip-1"));
        assertTrue(limiter.tryAcquire("register", "ip-1"));
    }

    @Test
    void resetPermiteNovaSerie() {
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), () -> 1_000L);

        limiter.tryAcquire("login", "ip-1");
        limiter.tryAcquire("login", "ip-1");
        limiter.reset("login", "ip-1");

        assertTrue(limiter.tryAcquire("login", "ip-1"));
    }

    @Test
    void clienteInativoEhRemovidoAposJanela() {
        AtomicLong clock = new AtomicLong(1_000L);
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), clock::get);

        limiter.tryAcquire("login", "inativo");
        assertEquals(1, limiter.trackedClients());

        clock.set(1_000L + 60_000L + 1L);
        // Requisição em outra chave dispara a varredura periódica.
        limiter.tryAcquire("login", "outro");

        assertEquals(1, limiter.trackedClients());
        assertTrue(limiter.tryAcquire("login", "inativo"));
    }

    @Test
    void limpezaNaoRemoveContagemDeClienteAtivo() {
        AtomicLong clock = new AtomicLong(1_000L);
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), clock::get);

        limiter.tryAcquire("login", "antigo");
        clock.set(30_000L);
        assertTrue(limiter.tryAcquire("login", "ativo"));
        assertTrue(limiter.tryAcquire("login", "ativo"));

        clock.set(61_000L);
        limiter.tryAcquire("login", "disparo");

        assertFalse(limiter.tryAcquire("login", "ativo"));
    }

    @Test
    void acessosConcorrentesRespeitamLimiteDaMesmaChave() throws Exception {
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), () -> 1_000L);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger permitidos = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                if (limiter.tryAcquire("login", "concorrente")) {
                    permitidos.incrementAndGet();
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(2, permitidos.get());
    }

    @Test
    void resetTryAcquireELimpezaConcorrentesNaoGeramEstadoInconsistente() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), clock::get);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> {
            start.await();
            for (int i = 0; i < 50; i++) {
                limiter.tryAcquire("login", "race");
            }
            return null;
        }));
        futures.add(executor.submit(() -> {
            start.await();
            for (int i = 0; i < 50; i++) {
                limiter.reset("login", "race");
            }
            return null;
        }));
        futures.add(executor.submit(() -> {
            start.await();
            for (int i = 0; i < 50; i++) {
                clock.incrementAndGet();
                limiter.tryAcquire("login", "outra");
            }
            return null;
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        limiter.reset("login", "race");
        assertTrue(limiter.tryAcquire("login", "race"));
    }

    @Test
    void tryAcquireWithRetryAfterCalculaSegundosRestantesComTetoEPisoUm() {
        AtomicLong clock = new AtomicLong(1_000L);
        ClientRateLimiter limiter = new ClientRateLimiter(properties(), clock::get);

        assertTrue(limiter.tryAcquireWithRetryAfter("login", "ip-1").allowed());
        assertTrue(limiter.tryAcquireWithRetryAfter("login", "ip-1").allowed());

        clock.set(16_000L); // 15 s decorridos de 60 s -> faltam exatos 45 s
        ClientRateLimiter.RateLimitDecision em15s =
                limiter.tryAcquireWithRetryAfter("login", "ip-1");
        assertFalse(em15s.allowed());
        assertEquals(45L, em15s.retryAfterSeconds());

        clock.set(16_001L); // 44_999 ms restantes -> arredondamento para cima = 45 s
        ClientRateLimiter.RateLimitDecision fracionario =
                limiter.tryAcquireWithRetryAfter("login", "ip-1");
        assertFalse(fracionario.allowed());
        assertEquals(45L, fracionario.retryAfterSeconds());

        clock.set(60_999L); // último milissegundo antes de expirar -> piso 1 s
        ClientRateLimiter.RateLimitDecision ultimoMs =
                limiter.tryAcquireWithRetryAfter("login", "ip-1");
        assertFalse(ultimoMs.allowed());
        assertEquals(1L, ultimoMs.retryAfterSeconds());

        clock.set(61_000L); // fronteira exata (oldest == cutoff) ainda retida -> piso 1 s
        ClientRateLimiter.RateLimitDecision fronteira =
                limiter.tryAcquireWithRetryAfter("login", "ip-1");
        assertFalse(fronteira.allowed());
        assertEquals(1L, fronteira.retryAfterSeconds());

        clock.set(61_001L); // expirou
        assertTrue(limiter.tryAcquireWithRetryAfter("login", "ip-1").allowed());
    }
}
