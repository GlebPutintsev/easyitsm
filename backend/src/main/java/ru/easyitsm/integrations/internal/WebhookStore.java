package ru.easyitsm.integrations.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;

/** Таблицы {@code integration_webhook} и {@code integration_outbound_policy}. Строки ограничивает RLS. */
@Component
class WebhookStore {

    /** Получатель без ключа подписи. */
    record Webhook(UUID id, String name, String url, boolean enabled, Instant lastUsedAt, long version,
                   Instant createdAt, Instant updatedAt) {
    }

    /** Разрешённые адреса арендатора; version 0 и пустой список, пока ADM ничего не задал. */
    record Policy(List<String> allowedHosts, long version) {
    }

    private static final String COLUMNS = "id, name, url, enabled, last_used_at, version, created_at, updated_at";

    private final JdbcClient jdbc;

    WebhookStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Webhook> list() {
        return jdbc.sql("select " + COLUMNS + " from integration_webhook order by name, id").query(this::map).list();
    }

    Optional<Webhook> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_webhook where id = :id").param("id", id)
                .query(this::map).optional();
    }

    Optional<String> secret(UUID id) {
        return jdbc.sql("select secret from integration_webhook where id = :id").param("id", id)
                .query(String.class).optional();
    }

    UUID insert(String name, String url, boolean enabled, String encryptedSecret) {
        UUID id = Ids.newId();
        var now = SourceStore.now();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into integration_webhook (id, tenant_id, name, url, secret, enabled, created_at, created_by,
                    updated_at, updated_by, version)
                values (:id, :tenant, :name, :url, :secret, :enabled, :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("name", name).param("url", url)
                .param("secret", encryptedSecret).param("enabled", enabled).param("now", now).param("actor", actor)
                .update();
        return id;
    }

    boolean update(UUID id, long version, String name, String url, boolean enabled) {
        return jdbc.sql("""
                update integration_webhook set name = :name, url = :url, enabled = :enabled, updated_at = :now,
                       updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("name", name).param("url", url).param("enabled", enabled).param("now", SourceStore.now())
                .param("actor", CurrentActor.userId().orElse(null)).param("id", id).param("version", version)
                .update() == 1;
    }

    void rotateSecret(UUID id, String encryptedSecret) {
        jdbc.sql("""
                update integration_webhook set secret = :secret, updated_at = :now, updated_by = :actor,
                       version = version + 1
                 where id = :id""")
                .param("secret", encryptedSecret).param("now", SourceStore.now())
                .param("actor", CurrentActor.userId().orElse(null)).param("id", id).update();
    }

    void touch(UUID id) {
        jdbc.sql("update integration_webhook set last_used_at = now() where id = :id").param("id", id).update();
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from integration_webhook where id = :id").param("id", id).update() == 1;
    }

    Policy policy() {
        return jdbc.sql("select allowed_hosts, version from integration_outbound_policy").query((rs, n) -> {
            Array a = rs.getArray("allowed_hosts");
            return new Policy(a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList(),
                    rs.getLong("version"));
        }).optional().orElse(new Policy(List.of(), 0));
    }

    /** @return false — версия не совпала */
    boolean savePolicy(List<String> hosts, long version) {
        String array = hosts.stream().map(h -> "\"" + h.replace("\"", "") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        var now = SourceStore.now();
        UUID actor = CurrentActor.userId().orElse(null);
        return jdbc.sql("""
                insert into integration_outbound_policy (tenant_id, allowed_hosts, created_at, created_by, updated_at,
                    updated_by, version)
                values (:tenant, cast(:hosts as text[]), :now, :actor, :now, :actor, 1)
                on conflict (tenant_id) do update set allowed_hosts = excluded.allowed_hosts, updated_at = excluded.updated_at,
                    updated_by = excluded.updated_by, version = integration_outbound_policy.version + 1
                 where integration_outbound_policy.version = :version""")
                .param("tenant", TenantContext.require()).param("hosts", array).param("now", now).param("actor", actor)
                .param("version", version)
                .update() == 1;
    }

    private Webhook map(ResultSet rs, int n) throws SQLException {
        return new Webhook(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"),
                rs.getBoolean("enabled"), SourceStore.instant(rs, "last_used_at"), rs.getLong("version"),
                SourceStore.instant(rs, "created_at"), SourceStore.instant(rs, "updated_at"));
    }
}
