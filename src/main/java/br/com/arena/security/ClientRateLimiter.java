package br.com.arena.security;

import br.com.arena.exception.RateLimitSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Rate limiter em memória com janela deslizante.
 *
 * <p>Armazena contadores por (categoria, cliente) em {@link ConcurrentHashMap}.
 * Entradas inativas são removidas por varredura periódica e limitada, no máximo
 * uma vez por janela. {@link ConcurrentHashMap#compute} mantém a atualização de
 * cada chave atômica, incluindo a remoção condicional de filas vazias.</p>
 *
 * <p>É adequado a uma única instância; em múltiplas instâncias o store deve ser
 * compartilhado, preservando o contrato de {@link #tryAcquire(String, String)}.</p>
 */
@Component
public class ClientRateLimiter {

    private final SecurityHardeningProperties properties;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepAt = new AtomicLong();
    private final ConcurrentHashMap<Long, PasswordAttemptState> passwordStates = new ConcurrentHashMap<>(256);
    private final AtomicLong lastPasswordSweepAt = new AtomicLong();

    @Autowired
    public ClientRateLimiter(SecurityHardeningProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    ClientRateLimiter(SecurityHardeningProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean tryAcquire(String bucket, String clientKey) {
        return tryAcquireWithRetryAfter(bucket, clientKey).allowed();
    }

    /**
     * Tenta consumir uma permissão da janela deslizante; quando negado, calcula
     * o tempo mínimo restante (em segundos inteiros, mínimo {@code 1}) até a
     * entrada mais antiga expirar.
     */
    public RateLimitDecision tryAcquireWithRetryAfter(String bucket, String clientKey) {
        long now = clock.getAsLong();
        maybeSweep(now);

        String key = bucket + "|" + clientKey;
        long windowMillis = properties.windowMillis();
        int limit = properties.limitFor(bucket);
        RateLimitDecision[] decision = new RateLimitDecision[1];

        hits.compute(key, (ignored, timestamps) -> {
            ArrayDeque<Long> queue = timestamps == null ? new ArrayDeque<>() : timestamps;
            prune(queue, now);

            if (queue.size() >= limit) {
                long retryAfterSec = computeRetryAfterSeconds(queue.peekFirst(), windowMillis, now);
                decision[0] = new RateLimitDecision(false, retryAfterSec);
                return queue;
            }
            queue.addLast(now);
            decision[0] = new RateLimitDecision(true, 0L);
            return queue;
        });

        return decision[0];
    }

    public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
    }

    /**
     * Verifica sem consumir se a chave já atingiu o limite na janela atual.
     * Usado pelo fluxo de alteração de senha para bloquear a operação antes de
     * qualquer verificação BCrypt ou acesso ao banco.
     */
    public boolean isExhausted(String bucket, String clientKey) {
        String key = bucket + "|" + clientKey;
        boolean[] exhausted = new boolean[]{false};

        hits.computeIfPresent(key, (ignored, timestamps) -> {
            ArrayDeque<Long> queue = timestamps == null ? new ArrayDeque<>() : timestamps;
            prune(queue, clock.getAsLong());
            if (queue.isEmpty()) {
                return null;
            }
            exhausted[0] = queue.size() >= properties.limitFor(bucket);
            return queue;
        });

        return exhausted[0];
    }

    public void reset(String bucket, String clientKey) {
        hits.remove(bucket + "|" + clientKey);
    }

    /** Visível para testes: quantidade de chaves atualmente retidas. */
    int trackedClients() {
        return hits.size();
    }

    /** Visível para testes: tentativas registradas para uma chave na janela atual. */
    int attemptCount(String bucket, String clientKey) {
        ArrayDeque<Long> queue = hits.get(bucket + "|" + clientKey);
        if (queue == null) {
            return 0;
        }
        prune(queue, clock.getAsLong());
        return queue.size();
    }

    private void prune(ArrayDeque<Long> timestamps, long now) {
        long cutoff = now - properties.windowMillis();
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }
    }

    private void maybeSweep(long now) {
        long last = lastSweepAt.get();
        if (now - last >= properties.windowMillis()
                && lastSweepAt.compareAndSet(last, now)) {
            purgeExpired(now);
        }
    }

    private void purgeExpired(long now) {
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (ignored, timestamps) -> {
                ArrayDeque<Long> queue = timestamps == null ? new ArrayDeque<>() : timestamps;
                prune(queue, now);
                // null remove a entrada do mapa de forma condicional e segura.
                return queue.isEmpty() ? null : queue;
            });
        }
    }

    /**
     * Tenta admitir uma alteração de senha de forma atômica para uma conta.
     *
     * <p>Avalia a concorrência máxima por conta (inFlight) e o limite de falhas
     * por conta/IP na mesma operação de remap atômica. Se a requisição for admitida,
     * devolve um identificador único de operação. Se rejeitada, preserva integralmente
     * o estado ativo e devolve {@code null}.</p>
     */
    public UUID admitPasswordChange(long accountId, String rawIp) {
        return admitPasswordChangeWithDetails(accountId, rawIp).operationId();
    }

    /**
     * Tenta admitir uma alteração de senha de forma atômica para uma conta,
     * informando a origem da rejeição e o {@code Retry-After} quando negada.
     */
    public PasswordAdmissionResult admitPasswordChangeWithDetails(long accountId, String rawIp) {
        long now = clock.getAsLong();
        maybeSweepPasswordStates(now);

        String ip = sanitizeIp(rawIp);
        int maxConcurrent = properties.passwordChangeMaxConcurrent();
        int failureLimit = properties.passwordChangeRateLimit();
        long windowMillis = properties.windowMillis();
        PasswordAdmissionResult[] result = new PasswordAdmissionResult[1];

        passwordStates.compute(accountId, (id, state) -> {
            PasswordAttemptState s = state != null ? state : new PasswordAttemptState();
            s.pruneFailures(now, windowMillis);

            // 1. Limite de concorrência por conta:
            if (s.inFlightCount() >= maxConcurrent) {
                result[0] = new PasswordAdmissionResult(
                        null, RateLimitSource.PASSWORD_CHANGE_CONCURRENCY, 1L);
                return state;
            }

            // 2. Limite de falhas por conta/IP:
            if (s.failureCount(ip) >= failureLimit) {
                long retryAfterSec = computeRetryAfterSeconds(
                        s.oldestFailureAt(ip), windowMillis, now);
                result[0] = new PasswordAdmissionResult(
                        null, RateLimitSource.PASSWORD_CHANGE_RATE_LIMIT, retryAfterSec);
                return state;
            }

            // 3. Admissão concedida:
            UUID opId = UUID.randomUUID();
            s.addOperation(opId, ip, now);
            result[0] = new PasswordAdmissionResult(opId, null, 0L);
            return s;
        });

        return result[0];
    }

    private static long computeRetryAfterSeconds(Long oldestTimestamp, long windowMillis, long now) {
        long remainingMs = oldestTimestamp == null
                ? 1L
                : Math.max(1L, (oldestTimestamp + windowMillis) - now);
        return Math.max(1L, (remainingMs + 999L) / 1000L);
    }

    public record PasswordAdmissionResult(
            UUID operationId, RateLimitSource rejectionSource, long retryAfterSeconds) {
        public boolean admitted() {
            return operationId != null;
        }
    }

    /**
     * Conclui a operação de alteração de senha e libera a vaga de concorrência.
     *
     * <p>A conclusão é estritamente idempotente. Se o resultado for {@link Outcome#FAILED},
     * a falha é registrada na janela do IP de origem. Em seguida, as falhas expiradas
     * são podadas e o nó é removido condicionalmente se e somente se o estado estiver
     * totalmente vazio (sem operações ativas e sem falhas registradas).</p>
     */
    public void completePasswordChange(long accountId, UUID operationId, Outcome outcome) {
        if (operationId == null) {
            return;
        }
        long now = clock.getAsLong();
        long windowMillis = properties.windowMillis();

        passwordStates.computeIfPresent(accountId, (id, s) -> {
            Operation op = s.removeOperation(operationId);
            if (op == null) {
                // Conclusão duplicada ou operationId já removido: poda e limpa se vazio.
                s.pruneFailures(now, windowMillis);
                return s.isEmpty() ? null : s;
            }

            if (outcome == Outcome.FAILED) {
                s.recordFailure(op.ip(), now);
            }

            s.pruneFailures(now, windowMillis);

            if (s.isEmpty()) {
                return null;
            }
            return s;
        });
    }

    private void maybeSweepPasswordStates(long now) {
        long last = lastPasswordSweepAt.get();
        long windowMillis = properties.windowMillis();
        if (now - last >= windowMillis
                && lastPasswordSweepAt.compareAndSet(last, now)) {
            purgeExpiredPasswordStates(now, windowMillis);
        }
    }

    private void purgeExpiredPasswordStates(long now, long windowMillis) {
        try {
            sweepPasswordStates(now, windowMillis);
        } catch (Exception e) {
            // Isola falhas recuperáveis para que a varredura não interrompa
            // a admissão da requisição em curso. Erros fatais (VirtualMachineError) não são capturados.
        }
    }

    /** Visível para testes: executa a varredura de estados expirados sem suprimir exceções. */
    void sweepPasswordStates(long now, long windowMillis) {
        for (Long accountId : passwordStates.keySet()) {
            passwordStates.computeIfPresent(accountId, (id, s) -> {
                s.pruneFailures(now, windowMillis);
                // NUNCA remover se houver operações em andamento
                if (s.isEmpty()) {
                    return null;
                }
                return s;
            });
        }
    }

    private static String sanitizeIp(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return "unknown";
        }
        String normalized = IpAddressMatcher.normalize(rawIp);
        return normalized.isBlank() ? "unknown" : normalized;
    }

    /** Visível para testes: quantidade de operações em andamento para a conta. */
    int passwordInFlightCount(long accountId) {
        int[] count = new int[]{0};
        passwordStates.computeIfPresent(accountId, (id, s) -> {
            count[0] = s.inFlightCount();
            return s;
        });
        return count[0];
    }

    /** Visível para testes: falhas registradas na janela para a conta e IP. */
    int passwordFailureCount(long accountId, String rawIp) {
        String ip = sanitizeIp(rawIp);
        long now = clock.getAsLong();
        long windowMillis = properties.windowMillis();
        int[] count = new int[]{0};
        passwordStates.computeIfPresent(accountId, (id, s) -> {
            s.pruneFailures(now, windowMillis);
            count[0] = s.failureCount(ip);
            return s;
        });
        return count[0];
    }

    /** Visível para testes: contas com estado retido em memória. */
    int trackedPasswordStates() {
        return passwordStates.size();
    }

    /** Visível para testes: reset manual de estado de alteração de senha. */
    void resetPasswordChange(long accountId) {
        passwordStates.remove(accountId);
    }
}
