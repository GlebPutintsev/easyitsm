package ru.easyitsm.identity.internal;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenancyProperties;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Арендатор для эндпоинтов входа, где пользователь ещё неизвестен. В режиме SINGLE его ставит платформа,
 * в облачном режиме MULTI клиент передаёт код организации.
 */
@Component
class Tenancy {

    private final JdbcClient jdbc;
    private final TenancyProperties props;

    Tenancy(JdbcClient jdbc, TenancyProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    boolean multi() {
        return props.mode() == TenancyProperties.Mode.MULTI;
    }

    Optional<UUID> resolve(String code) {
        Optional<UUID> current = TenantContext.current();
        if (current.isPresent()) {
            return current;
        }
        if (code == null || code.isBlank() || code.length() > 63) {
            return Optional.empty();
        }
        return jdbc.sql("select identity_tenant_by_code(:code)").param("code", code.trim())
                .query(UUID.class).optional();
    }

    static <T> T in(UUID tenant, Supplier<T> action) {
        Object[] result = new Object[1];
        TenantContext.runAs(tenant, () -> result[0] = action.get());
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    static void run(UUID tenant, Runnable action) {
        TenantContext.runAs(tenant, action);
    }
}
