package ru.easyitsm.tickets.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Документы настроек арендатора: {@code workflow.<type>}, {@code priority_matrix}, {@code general}.
 * Нет строки — действуют значения по умолчанию с версией 0, первое сохранение требует {@code If-Match: "0"}.
 */
@Repository
class SettingsStore {

    private final JdbcClient jdbc;
    private final Json json;

    SettingsStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Workflow workflow(TicketType type) {
        return load("workflow." + type.code(), Workflow.class)
                .map(d -> d.value().withVersion(d.version()))
                .orElseGet(() -> DefaultWorkflows.of(type).withVersion(0L));
    }

    PriorityMatrix priorityMatrix() {
        return load("priority_matrix", PriorityMatrix.class)
                .map(d -> d.value().withVersion(d.version()))
                .orElseGet(() -> PriorityMatrix.defaults().withVersion(0L));
    }

    GeneralSettings general() {
        return load("general", GeneralSettings.class)
                .map(d -> d.value().withVersion(d.version()))
                .orElseGet(() -> GeneralSettings.defaults().withVersion(0L));
    }

    /** Сохраняет документ с проверкой версии, возвращает новую версию. */
    long save(String key, Object value, String ifMatch, long currentVersion) {
        ETags.requireMatch(ifMatch, currentVersion);
        var now = Sql.ts(Sql.now());
        UUID actor = CurrentActor.userId().orElse(null);
        String doc = json.write(value);
        if (currentVersion == 0) {
            int inserted = jdbc.sql("""
                    insert into ticket_settings (id, tenant_id, key, value, created_at, created_by, updated_at, updated_by, version)
                    values (:id, :tenant, :key, cast(:value as jsonb), :now, :actor, :now, :actor, 1)
                    on conflict (tenant_id, key) do nothing""")
                    .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("key", key)
                    .param("value", doc).param("now", now).param("actor", actor)
                    .update();
            if (inserted == 0) {
                throw TicketProblem.clash("version_conflict", "Настройки изменены другим пользователем. Обновите и повторите");
            }
            return 1;
        }
        int updated = jdbc.sql("""
                update ticket_settings set value = cast(:value as jsonb), updated_at = :now, updated_by = :actor,
                       version = version + 1
                 where key = :key and version = :version""")
                .param("value", doc).param("now", now).param("actor", actor).param("key", key)
                .param("version", currentVersion)
                .update();
        if (updated == 0) {
            throw TicketProblem.clash("version_conflict", "Настройки изменены другим пользователем. Обновите и повторите");
        }
        return currentVersion + 1;
    }

    private <T> Optional<Doc<T>> load(String key, Class<T> type) {
        return jdbc.sql("select value::text as value, version from ticket_settings where key = :key")
                .param("key", key)
                .query((rs, n) -> new Doc<>(json.read(rs.getString("value"), type), rs.getLong("version")))
                .optional();
    }

    private record Doc<T>(T value, long version) {
    }
}
