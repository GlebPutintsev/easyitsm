package ru.easyitsm.integrations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Таблица {@code integration_monitoring_source}. Арендатор — из {@link TenantContext}, строки ограничивает RLS. */
@Component
class SourceStore {

    private static final TypeReference<Map<String, MonitoringSource.Impact>> SEVERITY_MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, name, kind, token_hint, enabled, requester_id, group_id, min_severity, severity_map::text as severity_map,
            auto_resolve, resolution_code, last_event_at, version, created_at, updated_at""";

    /** Источник по токену: арендатор и id. */
    record TokenMatch(UUID tenantId, UUID sourceId) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    SourceStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Ищет по хешу токена во всех арендаторах (функция SECURITY DEFINER), до установки арендатора. */
    Optional<TokenMatch> byToken(String token) {
        return jdbc.sql("select tenant_id, id from integration_source_by_token(:h)").param("h", Tokens.hash(token))
                .query((rs, n) -> new TokenMatch(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
                .optional();
    }

    List<MonitoringSource> list() {
        return jdbc.sql("select " + COLUMNS + " from integration_monitoring_source order by name, id")
                .query(this::map).list();
    }

    Optional<MonitoringSource> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_monitoring_source where id = :id").param("id", id)
                .query(this::map).optional();
    }

    UUID insert(SourceInput in, String token) {
        UUID id = Ids.newId();
        OffsetDateTime now = now();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into integration_monitoring_source (id, tenant_id, name, kind, token_hash, token_hint, enabled,
                    requester_id, group_id, min_severity, severity_map, auto_resolve, resolution_code,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :name, :kind, :hash, :hint, :enabled, :requester, :group, :minSeverity,
                    cast(:map as jsonb), :autoResolve, :resolution, :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require())
                .param("name", in.name().strip()).param("kind", in.kind())
                .param("hash", Tokens.hash(token)).param("hint", Tokens.hint(token))
                .param("enabled", !Boolean.FALSE.equals(in.enabled()))
                .param("requester", in.requesterId()).param("group", in.groupId())
                .param("minSeverity", in.minSeverity())
                .param("map", json.writeValueAsString(in.severityMap()))
                .param("autoResolve", !Boolean.FALSE.equals(in.autoResolve()))
                .param("resolution", in.resolutionCode())
                .param("now", now).param("actor", actor)
                .update();
        return id;
    }

    /** @return false — версия не совпала */
    boolean update(UUID id, long version, SourceInput in) {
        return jdbc.sql("""
                update integration_monitoring_source set name = :name, kind = :kind, enabled = :enabled,
                       requester_id = :requester, group_id = :group, min_severity = :minSeverity,
                       severity_map = cast(:map as jsonb), auto_resolve = :autoResolve, resolution_code = :resolution,
                       updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("name", in.name().strip()).param("kind", in.kind())
                .param("enabled", Boolean.TRUE.equals(in.enabled()))
                .param("requester", in.requesterId()).param("group", in.groupId())
                .param("minSeverity", in.minSeverity())
                .param("map", json.writeValueAsString(in.severityMap()))
                .param("autoResolve", Boolean.TRUE.equals(in.autoResolve()))
                .param("resolution", in.resolutionCode())
                .param("now", now()).param("actor", CurrentActor.userId().orElse(null))
                .param("id", id).param("version", version)
                .update() == 1;
    }

    void rotateToken(UUID id, String token) {
        jdbc.sql("""
                update integration_monitoring_source set token_hash = :hash, token_hint = :hint, updated_at = :now,
                       updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("hash", Tokens.hash(token)).param("hint", Tokens.hint(token))
                .param("now", now()).param("actor", CurrentActor.userId().orElse(null)).param("id", id)
                .update();
    }

    void touch(UUID id) {
        jdbc.sql("update integration_monitoring_source set last_event_at = now() where id = :id").param("id", id)
                .update();
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from integration_monitoring_source where id = :id").param("id", id).update() == 1;
    }

    private MonitoringSource map(ResultSet rs, int n) throws SQLException {
        return new MonitoringSource(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getString("token_hint"),
                rs.getBoolean("enabled"),
                rs.getObject("requester_id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getString("min_severity"),
                json.readValue(rs.getString("severity_map"), SEVERITY_MAP),
                rs.getBoolean("auto_resolve"),
                rs.getString("resolution_code"),
                instant(rs, "last_event_at"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static OffsetDateTime now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
