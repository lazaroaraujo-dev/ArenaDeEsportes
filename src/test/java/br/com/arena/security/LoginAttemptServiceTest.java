package br.com.arena.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptServiceTest {

    /** Configuração aprovada: 5 falhas e 300 s de bloqueio. */
    private static SecurityHardeningProperties properties() {
        return new SecurityHardeningProperties(20, 5, 60, false, 5, 300);
    }

    @Test
    void bloqueiaAposCincoFalhasConsecutivas() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);

        for (int i = 0; i < 5; i++) {
            service.recordFailure("ip-1", "Usuario@Exemplo.invalid");
        }

        assertTrue(service.isLocked("ip-1", "usuario@exemplo.invalid"));
    }

    @Test
    void sucessoLimpaContador() {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);

        for (int i = 0; i < 4; i++) {
            service.recordFailure("ip-1", "usuario@exemplo.invalid");
        }
        service.recordSuccess("ip-1", "usuario@exemplo.invalid");
        service.recordFailure("ip-1", "usuario@exemplo.invalid");

        assertFalse(service.isLocked("ip-1", "usuario@exemplo.invalid"));
    }

    @Test
    void bloqueioExpiraAutomaticamente() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);

        for (int i = 0; i < 5; i++) {
            service.recordFailure("ip-1", "usuario@exemplo.invalid");
        }
        assertTrue(service.isLocked("ip-1", "usuario@exemplo.invalid"));

        clock.set(1_000L + 300_000L + 1L);
        assertFalse(service.isLocked("ip-1", "usuario@exemplo.invalid"));
    }

    @Test
    void ipDiferenteNaoEhAfetado() {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);

        for (int i = 0; i < 5; i++) {
            service.recordFailure("ip-1", "usuario@exemplo.invalid");
        }

        assertTrue(service.isLocked("ip-1", "usuario@exemplo.invalid"));
        assertFalse(service.isLocked("ip-2", "usuario@exemplo.invalid"));
    }

    @Test
    void contaDiferenteNoMesmoIpNaoEhAfetada() {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);

        for (int i = 0; i < 5; i++) {
            service.recordFailure("ip-1", "vitima@exemplo.invalid");
        }

        assertTrue(service.isLocked("ip-1", "vitima@exemplo.invalid"));
        assertFalse(service.isLocked("ip-1", "outra@exemplo.invalid"));
    }

    @Test
    void falhasAbaixoDoLimiteSaoRemovidasPorInatividade() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);

        service.recordFailure("ip-1", "inativo@exemplo.invalid");
        service.recordFailure("ip-1", "inativo@exemplo.invalid");
        assertEquals(1, service.trackedAttempts());

        clock.set(1_000L + 300_000L + 1L);
        // Operação em outra chave dispara a varredura periódica.
        service.isLocked("ip-2", "outro@exemplo.invalid");

        assertEquals(0, service.trackedAttempts());
    }

    @Test
    void bloqueioExpiradoEhRemovidoSemNovaConsultaDaMesmaChave() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);

        for (int i = 0; i < 5; i++) {
            service.recordFailure("ip-1", "bloqueado@exemplo.invalid");
        }
        assertTrue(service.isLocked("ip-1", "bloqueado@exemplo.invalid"));

        clock.set(1_000L + 300_000L + 1L);
        service.isLocked("ip-2", "disparo@exemplo.invalid");

        assertEquals(0, service.trackedAttempts());
    }

    @Test
    void tentativasConcorrentesNaoPerdemIncrementos() throws Exception {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                service.recordFailure("ip-concorrente", "conta@exemplo.invalid");
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertTrue(service.isLocked("ip-concorrente", "conta@exemplo.invalid"));
        assertEquals(1, service.trackedAttempts());
    }

    @Test
    void sucessoEResetConcorrentesNaoGeramEstadoInconsistente() throws Exception {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        String ip = "ip-race";
        String email = "raca@exemplo.invalid";

        service.recordFailure(ip, email);
        assertEquals(1, service.trackedAttempts());
        assertEquals(1, service.trackedAccountAttempts());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch falhaConcluida = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        // A limpeza só é disparada depois da falha: assim o resultado é
        // determinístico e a asserção prova que as duas camadas foram limpas.
        futures.add(executor.submit(() -> {
            start.await();
            service.recordFailure(ip, email);
            falhaConcluida.countDown();
            return null;
        }));
        futures.add(executor.submit(() -> {
            start.await();
            falhaConcluida.await();
            service.recordSuccess(ip, email);
            service.reset(ip, email);
            return null;
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(0, service.trackedAttempts(), "camada IP+conta deve estar limpa");
        assertEquals(0, service.trackedAccountAttempts(), "camada por conta deve estar limpa");
        assertFalse(service.isLocked(ip, email));
    }

    @Test
    void chaveNaoContemEmailEmTextoPuro() {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        String email = "privacidade@exemplo.invalid";

        String chave = service.keyFor("ip-1", email);

        assertFalse(chave.contains(email));
        assertFalse(chave.contains("privacidade"));
        assertTrue(chave.matches("ip-1\\|[0-9a-f]{64}"));
    }

    @Test
    void limpezaConcorrenteComRegistroDeFalhas() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);
        String ip = "ip-limpeza";
        String email = "conta@exemplo.invalid";

        for (int i = 0; i < 3; i++) {
            service.recordFailure(ip, email);
        }
        assertEquals(1, service.trackedAttempts());
        assertEquals(1, service.trackedAccountAttempts());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch varreduraConcluida = new CountDownLatch(1);
        AtomicBoolean varreduraRemoveuAntiga = new AtomicBoolean(false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> {
            start.await();
            varreduraConcluida.await();
            // Cinco falhas recentes após a varredura geram bloqueio ativo no
            // instante atual, independentemente das falhas antigas removidas.
            for (int i = 0; i < 5; i++) {
                service.recordFailure(ip, email);
            }
            return null;
        }));
        futures.add(executor.submit(() -> {
            start.await();
            clock.addAndGet(300_001L);
            service.isLocked("disparo", "outra@exemplo.invalid");
            varreduraRemoveuAntiga.set(service.trackedAttempts() == 0);
            varreduraConcluida.countDown();
            return null;
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertTrue(varreduraRemoveuAntiga.get(),
                "a varredura deve remover a entrada antiga antes das falhas recentes");
        assertTrue(service.isLocked(ip, email), "as falhas recentes devem gerar bloqueio ativo");
        assertEquals(1, service.trackedAttempts());
    }

    @Test
    void limpezaConcorrenteComConsultaDeBloqueio() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);

        for (int i = 0; i < 5; i++) {
            service.recordFailure("ip-consulta", "conta@exemplo.invalid");
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> {
            start.await();
            for (int i = 0; i < 200; i++) {
                service.isLocked("ip-consulta", "conta@exemplo.invalid");
            }
            return null;
        }));
        futures.add(executor.submit(() -> {
            start.await();
            clock.addAndGet(300_001L);
            service.isLocked("disparo", "outra@exemplo.invalid");
            return null;
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(0, service.trackedAttempts());
    }

    @Test
    void resetConcorrenteComRecordFailure() throws Exception {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        String ip = "ip-reset";
        String email = "conta@exemplo.invalid";

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch falhasConcluidas = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> {
            start.await();
            for (int i = 0; i < 100; i++) {
                service.recordFailure(ip, email);
            }
            falhasConcluidas.countDown();
            return null;
        }));
        futures.add(executor.submit(() -> {
            start.await();
            falhasConcluidas.await();
            // Reset depois das falhas: deve limpar as duas camadas.
            service.reset(ip, email);
            return null;
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(0, service.trackedAttempts(), "reset deve limpar a camada IP+conta");
        assertEquals(0, service.trackedAccountAttempts(), "reset deve limpar a camada por conta");
        assertFalse(service.isLocked(ip, email));
    }

    @Test
    void recordSuccessConcorrenteComRecordFailure() throws Exception {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        String ip = "ip-sucesso";
        String email = "conta@exemplo.invalid";

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch falhasConcluidas = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        futures.add(executor.submit(() -> {
            start.await();
            for (int i = 0; i < 100; i++) {
                service.recordFailure(ip, email);
            }
            falhasConcluidas.countDown();
            return null;
        }));
        futures.add(executor.submit(() -> {
            start.await();
            falhasConcluidas.await();
            // Sucesso depois das falhas: deve limpar as duas camadas.
            service.recordSuccess(ip, email);
            return null;
        }));

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertEquals(0, service.trackedAttempts(), "sucesso deve limpar a camada IP+conta");
        assertEquals(0, service.trackedAccountAttempts(), "sucesso deve limpar a camada por conta");
        assertFalse(service.isLocked(ip, email));
    }

    @Test
    void tentativaRecenteNaoDesapareceNaLimpeza() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);

        // 1. Tentativa antiga, registrada muito antes da janela de retenção.
        service.recordFailure("ip-antiga", "antiga@exemplo.invalid");

        // 2. Outra tentativa em instante posterior, ainda dentro da janela
        // contada a partir da última varredura.
        clock.set(200_000L);
        service.recordFailure("ip-recente", "recente@exemplo.invalid");
        assertEquals(2, service.trackedAttempts());

        // 3/4. Avança mais de 300 s desde a última varredura e dispara a limpeza
        // por uma terceira chave, sem consultar as duas entradas existentes.
        clock.set(400_000L);
        service.isLocked("disparo", "outra@exemplo.invalid");

        // 5. A antiga (1_000 ms) é removida; a recente (200_000 ms) permanece
        // com seu histórico — quatro falhas adicionais devem bloqueá-la.
        assertEquals(1, service.trackedAttempts());
        for (int i = 0; i < 4; i++) {
            service.recordFailure("ip-recente", "recente@exemplo.invalid");
        }
        assertTrue(service.isLocked("ip-recente", "recente@exemplo.invalid"));
    }

    @Test
    void operacoesMistasConcorrentesNaoGeramDeadlock() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger falhas = new AtomicInteger();
        AtomicInteger consultas = new AtomicInteger();
        AtomicInteger limpezas = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            final int operacao = i;
            futures.add(executor.submit(() -> {
                start.await();
                for (int j = 0; j < 50; j++) {
                    switch (operacao % 4) {
                        case 0 -> {
                            service.recordFailure("ip-misto", "conta@exemplo.invalid");
                            falhas.incrementAndGet();
                        }
                        case 1 -> {
                            service.isLocked("ip-misto", "conta@exemplo.invalid");
                            consultas.incrementAndGet();
                        }
                        case 2 -> {
                            service.reset("ip-misto", "conta@exemplo.invalid");
                            limpezas.incrementAndGet();
                        }
                        default -> {
                            clock.incrementAndGet();
                            service.isLocked("disparo", "outra@exemplo.invalid");
                        }
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        // Asserções reais antes de qualquer limpeza final: todas as operações
        // executaram e nenhum mapa cresceu além de uma entrada para a chave.
        assertTrue(falhas.get() > 0, "falhas deveriam ter sido registradas");
        assertTrue(consultas.get() > 0, "consultas deveriam ter sido executadas");
        assertTrue(limpezas.get() > 0, "limpezas deveriam ter sido executadas");
        assertTrue(service.trackedAttempts() <= 1, "no máximo uma entrada IP+conta");
        assertTrue(service.trackedAccountAttempts() <= 1, "no máximo uma entrada por conta");

        service.reset("ip-misto", "conta@exemplo.invalid");
        assertFalse(service.isLocked("ip-misto", "conta@exemplo.invalid"));
    }

    @Test
    void bloqueioAposCincoFalhasSimultaneas() throws Exception {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                service.recordFailure("ip-simultaneo", "conta@exemplo.invalid");
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertTrue(service.isLocked("ip-simultaneo", "conta@exemplo.invalid"));
        assertEquals(1, service.trackedAttempts());
    }

    @Test
    void limpezaAutomaticaAposTrescentosSegundosDeInatividade() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);

        service.recordFailure("ip-antigo", "antigo@exemplo.invalid");
        clock.set(1_000L + 300_001L);
        service.isLocked("disparo", "outra@exemplo.invalid");

        assertEquals(0, service.trackedAttempts());
    }

    private static SecurityHardeningProperties accountProperties() {
        return new SecurityHardeningProperties(20, 5, 60, false, "", 5, 300, 3, 10);
    }

    @Test
    void mesmoEmailDeIpsDiferentesDisparaCamadaPorConta() {
        LoginAttemptService service = new LoginAttemptService(accountProperties(), () -> 1_000L);

        service.recordFailure("ip-1", "conta@exemplo.invalid");
        service.recordFailure("ip-2", "conta@exemplo.invalid");
        service.recordFailure("ip-3", "conta@exemplo.invalid");

        assertTrue(service.isLocked("ip-4", "conta@exemplo.invalid"));
        assertEquals(1, service.trackedAccountAttempts());
    }

    @Test
    void emailsDiferentesMesmoIpNaoDisparamContaAlheia() {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);

        for (int i = 0; i < 5; i++) {
            service.recordFailure("ip-compartilhado", "vitima@exemplo.invalid");
        }

        assertTrue(service.isLocked("ip-compartilhado", "vitima@exemplo.invalid"));
        assertFalse(service.isLocked("ip-compartilhado", "outra@exemplo.invalid"));
    }

    @Test
    void bloqueioPorContaExpiraAutomaticamente() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(accountProperties(), clock::get);

        service.recordFailure("ip-1", "conta@exemplo.invalid");
        service.recordFailure("ip-2", "conta@exemplo.invalid");
        service.recordFailure("ip-3", "conta@exemplo.invalid");
        assertTrue(service.isLocked("ip-4", "conta@exemplo.invalid"));

        clock.set(1_000L + 10_001L);
        assertFalse(service.isLocked("ip-5", "conta@exemplo.invalid"));
    }

    @Test
    void contaNaoFicaBloqueadaPermanente() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(accountProperties(), clock::get);

        for (int i = 1; i <= 3; i++) {
            service.recordFailure("ip-" + i, "conta@exemplo.invalid");
        }
        assertTrue(service.isLocked("ip-9", "conta@exemplo.invalid"));

        clock.set(11_002L);
        assertFalse(service.isLocked("ip-10", "conta@exemplo.invalid"));

        service.recordFailure("ip-11", "conta@exemplo.invalid");
        service.recordFailure("ip-12", "conta@exemplo.invalid");
        service.recordFailure("ip-13", "conta@exemplo.invalid");
        assertTrue(service.isLocked("ip-14", "conta@exemplo.invalid"));

        clock.set(22_004L);
        assertFalse(service.isLocked("ip-15", "conta@exemplo.invalid"));
    }

    @Test
    void sucessoLimpaContadoresPorIpEPorConta() {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);

        service.recordFailure("ip-1", "conta@exemplo.invalid");
        service.recordFailure("ip-2", "conta@exemplo.invalid");
        assertEquals(1, service.trackedAccountAttempts());

        service.recordSuccess("ip-1", "conta@exemplo.invalid");

        assertEquals(0, service.trackedAccountAttempts());
        assertEquals(1, service.trackedAttempts(), "apenas a tentativa do ip-2 permanece");
        assertFalse(service.isLocked("ip-3", "conta@exemplo.invalid"));
    }

    @Test
    void concorrenciaDistribuidaNaoPerdeContagemPorConta() throws Exception {
        LoginAttemptService service = new LoginAttemptService(accountProperties(), () -> 1_000L);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            final int indice = i;
            futures.add(executor.submit(() -> {
                start.await();
                service.recordFailure("ip-conc-" + indice, "conta@exemplo.invalid");
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertTrue(service.isLocked("ip-novo", "conta@exemplo.invalid"));
        assertEquals(1, service.trackedAccountAttempts());
    }

    @Test
    void limpezaRemoveContaInativa() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptService service = new LoginAttemptService(accountProperties(), clock::get);

        service.recordFailure("ip-1", "inativa@exemplo.invalid");
        assertEquals(1, service.trackedAccountAttempts());

        clock.set(1_300_001L);
        service.isLocked("disparo", "outra@exemplo.invalid");

        assertEquals(0, service.trackedAccountAttempts());
    }

    @Test
    void chaveDaContaNaoContemEmailEmTextoPuro() {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        String email = "privacidade@exemplo.invalid";

        String chave = service.accountKeyFor(email);

        assertFalse(chave.contains(email));
        assertFalse(chave.contains("privacidade"));
        assertTrue(chave.matches("[0-9a-f]{64}"));
    }

    @Test
    void bloqueioPorContaAposVinteFalhasComRelogioControlado() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);
        String email = "conta@exemplo.invalid";

        for (int i = 1; i <= 20; i++) {
            service.recordFailure("ip-" + i, email);
        }

        assertTrue(service.isLocked("ip-novo", email));
        assertEquals(1, service.trackedAccountAttempts());

        clock.set(1_000L + 60_001L);
        assertFalse(service.isLocked("ip-outro", email));
        assertEquals(0, service.trackedAccountAttempts());
    }

    @Test
    void expiracaoLogicaDaContaNaoDependeDaVarredura() {
        AtomicLong clock = new AtomicLong(1_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);
        String email = "conta@exemplo.invalid";

        for (int i = 1; i <= 20; i++) {
            service.recordFailure("ip-" + i, email);
        }
        assertTrue(service.isLocked("ip-novo", email));

        clock.set(1_000L + 60_001L);
        // Nenhuma operação disparou varredura: a entrada ainda existe fisicamente.
        assertEquals(1, service.trackedAccountAttempts());

        // A consulta direta remove o bloqueio expirado, sem esperar a varredura.
        assertFalse(service.isLocked("ip-outro", email));
        assertEquals(0, service.trackedAccountAttempts());
    }

    @Test
    void limpezaFisicaDaContaOcorreAposIntervaloDaVarredura() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);
        String email = "conta@exemplo.invalid";

        service.recordFailure("ip-1", email);
        assertEquals(1, service.trackedAccountAttempts());

        // 60 s é a retenção lógica da camada por conta, mas a varredura é
        // disparada no máximo a cada 300 s.
        clock.set(1_060_001L);
        assertEquals(1, service.trackedAccountAttempts());

        clock.set(1_300_001L);
        service.isLocked("disparo", "outra@exemplo.invalid");

        assertEquals(0, service.trackedAccountAttempts(),
                "a limpeza física pode ocorrer depois da expiração lógica");
    }

    @Test
    void rajadaConcorrenteDemonstraQueLimiteNaoEstrito() throws Exception {
        LoginAttemptService service = new LoginAttemptService(properties(), () -> 1_000L);
        String email = "conta@exemplo.invalid";
        int quantidade = 20;

        ExecutorService executor = Executors.newFixedThreadPool(quantidade);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger admitidos = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < quantidade; i++) {
            final int indice = i;
            futures.add(executor.submit(() -> {
                start.await();
                if (!service.isLocked("ip-" + indice, email)) {
                    admitidos.incrementAndGet();
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        // Antes de qualquer falha registrada, todas as checagens passam:
        // o limite é aplicado no registro, não na admissão (TOCTOU conhecido).
        assertEquals(quantidade, admitidos.get());

        for (int i = 0; i < quantidade; i++) {
            service.recordFailure("ip-" + i, email);
        }

        assertTrue(service.isLocked("ip-novo", email));
        assertEquals(1, service.trackedAccountAttempts());
    }

    @Test
    void checkLockCalculaRetryAfterParaIpContaECombinacaoComExpiracaoAtomica() {
        AtomicLong clock = new AtomicLong(1_000L);
        // IP lock: 5 falhas / 300 s; conta lock: 3 falhas / 60 s
        SecurityHardeningProperties props =
                new SecurityHardeningProperties(20, 5, 60, false, "", 5, 300, 3, 60);
        LoginAttemptService service = new LoginAttemptService(props, clock::get);

        // 1. Apenas conta bloqueada (3 IPs diferentes x 1 falha cada = 3 na conta -> 60 s)
        String contaA = "conta-a@exemplo.invalid";
        service.recordFailure("ip-1", contaA);
        service.recordFailure("ip-2", contaA);
        service.recordFailure("ip-3", contaA);

        clock.set(11_000L); // +10 s -> faltam 50 s
        LoginAttemptService.LockCheckResult lockConta = service.checkLock("ip-4", contaA);
        assertTrue(lockConta.locked());
        assertEquals(50L, lockConta.retryAfterSeconds());

        // 2. Apenas IP bloqueado (5 falhas no mesmo IP com limite de conta alto)
        SecurityHardeningProperties propsIp =
                new SecurityHardeningProperties(20, 5, 60, false, "", 5, 300, 50, 60);
        AtomicLong clockIp = new AtomicLong(1_000L);
        LoginAttemptService serviceIp = new LoginAttemptService(propsIp, clockIp::get);
        String contaB = "conta-b@exemplo.invalid";
        for (int i = 0; i < 5; i++) {
            serviceIp.recordFailure("ip-unico", contaB);
        }
        clockIp.set(20_000L); // +19 s -> faltam 281 s
        LoginAttemptService.LockCheckResult lockIp = serviceIp.checkLock("ip-unico", contaB);
        assertTrue(lockIp.locked());
        assertEquals(281L, lockIp.retryAfterSeconds());

        // 3. Ambos ativos e expiração atômica de um mapa preservando o outro
        AtomicLong clockMisto = new AtomicLong(1_000L);
        SecurityHardeningProperties propsMisto =
                new SecurityHardeningProperties(20, 5, 60, false, "", 5, 300, 6, 60);
        LoginAttemptService serviceMisto = new LoginAttemptService(propsMisto, clockMisto::get);
        String contaMista = "mista@exemplo.invalid";
        // t=1_000: 5 falhas no ip-1 -> bloqueio de IP até 301_000
        for (int i = 0; i < 5; i++) {
            serviceMisto.recordFailure("ip-1", contaMista);
        }
        // t=280_000: 1 falha no ip-2 -> completa 6 falhas na conta -> bloqueio de conta até 340_000
        clockMisto.set(280_000L);
        serviceMisto.recordFailure("ip-2", contaMista);

        // Em t=290_000: ip-1 falta 11 s (até 301_000), conta falta 50 s (até 340_000) -> max = 50 s
        clockMisto.set(290_000L);
        assertEquals(2, serviceMisto.trackedAttempts(), "ip-1 e ip-2 estão retidos antes da expiração");
        LoginAttemptService.LockCheckResult ambosAtivos = serviceMisto.checkLock("ip-1", contaMista);
        assertTrue(ambosAtivos.locked());
        assertEquals(50L, ambosAtivos.retryAfterSeconds());

        // Em t=302_000: bloqueio de ip-1 expirou e é removido atomicamente (resta apenas ip-2),
        // enquanto a conta segue bloqueada (38 s).
        clockMisto.set(302_000L);
        LoginAttemptService.LockCheckResult ipExpiradoContaAtiva =
                serviceMisto.checkLock("ip-1", contaMista);
        assertTrue(ipExpiradoContaAtiva.locked());
        assertEquals(38L, ipExpiradoContaAtiva.retryAfterSeconds());
        assertEquals(1, serviceMisto.trackedAttempts(),
                "lock expirado de ip-1 deve ter sido removido, restando apenas ip-2");
        assertEquals(1, serviceMisto.trackedAccountAttempts(), "lock ativo de conta deve permanecer");

        // Em t=340_001: conta também expira
        clockMisto.set(340_001L);
        LoginAttemptService.LockCheckResult ambosExpirados = serviceMisto.checkLock("ip-1", contaMista);
        assertFalse(ambosExpirados.locked());
        assertEquals(0L, ambosExpirados.retryAfterSeconds());
    }

    @Test
    void checkLockRecordFailureERecordSuccessConcorrentesNaoGeramDeadlockNemRetryInvalido()
            throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LoginAttemptService service = new LoginAttemptService(properties(), clock::get);
        String email = "concorrente-check@exemplo.invalid";
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            final int op = i;
            futures.add(executor.submit(() -> {
                start.await();
                for (int j = 0; j < 100; j++) {
                    switch (op % 3) {
                        case 0 -> service.recordFailure("ip-c", email);
                        case 1 -> {
                            LoginAttemptService.LockCheckResult check =
                                    service.checkLock("ip-c", email);
                            if (check.locked()) {
                                assertTrue(check.retryAfterSeconds() >= 1L);
                            } else {
                                assertEquals(0L, check.retryAfterSeconds());
                            }
                        }
                        default -> service.recordSuccess("ip-c", email);
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        service.reset("ip-c", email);
        LoginAttemptService.LockCheckResult limpo = service.checkLock("ip-c", email);
        assertFalse(limpo.locked());
        assertEquals(0L, limpo.retryAfterSeconds());
    }

}
