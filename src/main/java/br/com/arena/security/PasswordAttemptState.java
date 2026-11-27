package br.com.arena.security;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Estado encapsulado de tentativas e operações em andamento para uma conta de usuário.
 *
 * <p>Todas as mutações devem ser executadas exclusivamente dentro de funções de
 * remap atômicas do {@link java.util.concurrent.ConcurrentHashMap}. Nenhuma coleção
 * interna é exposta.</p>
 */
final class PasswordAttemptState {

    private final Map<UUID, Operation> operations;
    private final Map<String, ArrayDeque<Long>> failuresByIp;

    PasswordAttemptState() {
        this.operations = new HashMap<>();
        this.failuresByIp = new HashMap<>();
    }

    void addOperation(UUID opId, String ip, long startedAt) {
        operations.put(opId, new Operation(ip, startedAt));
    }

    Operation removeOperation(UUID opId) {
        return operations.remove(opId);
    }

    int inFlightCount() {
        return operations.size();
    }

    void recordFailure(String ip, long now) {
        failuresByIp.computeIfAbsent(ip, k -> new ArrayDeque<>()).addLast(now);
    }

    int failureCount(String ip) {
        ArrayDeque<Long> queue = failuresByIp.get(ip);
        return queue != null ? queue.size() : 0;
    }

    Long oldestFailureAt(String ip) {
        ArrayDeque<Long> queue = failuresByIp.get(ip);
        return queue != null ? queue.peekFirst() : null;
    }

    void pruneFailures(long now, long windowMillis) {
        long cutoff = now - windowMillis;
        Iterator<Map.Entry<String, ArrayDeque<Long>>> it = failuresByIp.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ArrayDeque<Long>> entry = it.next();
            ArrayDeque<Long> queue = entry.getValue();
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
                queue.pollFirst();
            }
            if (queue.isEmpty()) {
                it.remove();
            }
        }
    }

    boolean isEmpty() {
        return operations.isEmpty() && failuresByIp.isEmpty();
    }
}
