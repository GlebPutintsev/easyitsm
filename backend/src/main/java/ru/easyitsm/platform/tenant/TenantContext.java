package ru.easyitsm.platform.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Арендатор текущего потока. Устанавливается фильтром HTTP-запроса или явно в фоновых задачах
 * через {@link #runAs(UUID, Runnable)}. Без арендатора RLS не вернёт ни одной строки.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID tenant = CURRENT.get();
        if (tenant == null) {
            throw new IllegalStateException("Арендатор не установлен для текущего потока");
        }
        return tenant;
    }

    public static void runAs(UUID tenantId, Runnable action) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static <T> T callAs(UUID tenantId, Callable<T> action) throws Exception {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return action.call();
        } finally {
            restore(previous);
        }
    }

    static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    static void clear() {
        CURRENT.remove();
    }

    private static void restore(UUID previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
