package ru.easyitsm.integrations.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Ограничение частоты запросов источника в пределах пода: окно в одну минуту. Защищает от шторма алертов
 * и от подбора нагрузки по украденному токену; общий предел на кластер даёт Ingress.
 */
@Component
class RateLimiter {

    private record Window(long minute, int count) {
    }

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final IntegrationsProperties props;

    RateLimiter(IntegrationsProperties props) {
        this.props = props;
    }

    boolean tryAcquire(UUID sourceId) {
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(sourceId, (k, old) -> old == null || old.minute() != minute
                ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        }
        return w.count() <= props.ratePerMinute();
    }
}
