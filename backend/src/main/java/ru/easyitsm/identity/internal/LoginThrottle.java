package ru.easyitsm.identity.internal;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.web.ApiException;

/**
 * Ограничение неудачных входов с одного адреса (SEC-05), в дополнение к блокировке учётной записи:
 * защищает от перебора паролей по многим логинам. Счётчик в памяти узла.
 * TODO(devsecops): общий счётчик в Valkey для нескольких подов api.
 */
@Component
class LoginThrottle {

    private final IdentityProperties.Lockout props;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    LoginThrottle(IdentityProperties props) {
        this.props = props.lockout();
    }

    void check(String ip) {
        Deque<Instant> attempts = failures.get(key(ip));
        if (attempts == null) {
            return;
        }
        synchronized (attempts) {
            prune(attempts, Instant.now());
            if (attempts.size() >= props.ipMaxFailures()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                        "Слишком много попыток входа. Повторите позже");
            }
        }
    }

    void recordFailure(String ip) {
        if (failures.size() > 10_000) {
            evictStale();
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key(ip), k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts, Instant.now());
            attempts.addLast(Instant.now());
        }
    }

    void sleepAfterFailure(int consecutiveFailures) {
        long delay = Math.min(props.failureDelay().toMillis() * Math.max(consecutiveFailures, 1),
                props.maxDelay().toMillis());
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Счётчик живёт на узлах api, где нет планировщика, поэтому очищается по ходу работы. */
    void evictStale() {
        Instant now = Instant.now();
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    private void prune(Deque<Instant> attempts, Instant now) {
        Instant border = now.minus(props.ipWindow());
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(border)) {
            attempts.pollFirst();
        }
    }

    private static String key(String ip) {
        return ip == null ? "unknown" : ip;
    }
}
