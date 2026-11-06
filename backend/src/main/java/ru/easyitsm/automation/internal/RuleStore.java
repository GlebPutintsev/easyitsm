package ru.easyitsm.automation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Таблица {@code automation_rule}. Арендатор — из {@link TenantContext}, строки ограничивает RLS. */
@Component
class RuleStore {

    private static final TypeReference<List<Action>> ACTIONS = new TypeReference<>() {
    };
    private static final String COLUMNS = """
            id, name, description, trigger, conditions::text as conditions, actions::text as actions, enabled,
            position, version, created_at, updated_at, updated_by""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    RuleStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<Rule> list() {
        return jdbc.sql("select " + COLUMNS + " from automation_rule order by position, name, id").query(this::map).list();
    }

    /** Включённые правила события в порядке выполнения. */
    List<Rule> enabledFor(Trigger trigger) {
        return jdbc.sql("select " + COLUMNS + " from automation_rule where trigger = :t and enabled order by position, id")
                .param("t", trigger.code()).query(this::map).list();
    }

    Optional<Rule> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from automation_rule where id = :id").param("id", id).query(this::map)
                .optional();
    }

    UUID insert(RuleInput r) {
        UUID id = Ids.newId();
        OffsetDateTime now = now();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into automation_rule (id, tenant_id, name, description, trigger, conditions, actions, enabled,
                    position, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :name, :description, :trigger, cast(:conditions as jsonb), cast(:actions as jsonb),
                    :enabled, :position, :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require())
                .param("name", r.name().strip()).param("description", r.description() == null ? "" : r.description())
                .param("trigger", r.trigger())
                .param("conditions", json.writeValueAsString(r.conditions() == null ? Conditions.always() : r.conditions()))
                .param("actions", json.writeValueAsString(r.actions()))
                .param("enabled", Boolean.TRUE.equals(r.enabled()))
                .param("position", r.position() == null ? 0 : r.position())
                .param("now", now).param("actor", actor)
                .update();
        return id;
    }

    /** @return false — версия не совпала (правило изменили параллельно) */
    boolean update(UUID id, long version, RuleInput r) {
        return jdbc.sql("""
                update automation_rule set name = :name, description = :description, trigger = :trigger,
                       conditions = cast(:conditions as jsonb), actions = cast(:actions as jsonb), enabled = :enabled,
                       position = :position, updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("name", r.name().strip()).param("description", r.description() == null ? "" : r.description())
                .param("trigger", r.trigger())
                .param("conditions", json.writeValueAsString(r.conditions() == null ? Conditions.always() : r.conditions()))
                .param("actions", json.writeValueAsString(r.actions()))
                .param("enabled", Boolean.TRUE.equals(r.enabled()))
                .param("position", r.position() == null ? 0 : r.position())
                .param("now", now()).param("actor", CurrentActor.userId().orElse(null))
                .param("id", id).param("version", version)
                .update() == 1;
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from automation_rule where id = :id").param("id", id).update() == 1;
    }

    private Rule map(ResultSet rs, int n) throws SQLException {
        return new Rule(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("trigger"),
                json.readValue(rs.getString("conditions"), Conditions.class),
                json.readValue(rs.getString("actions"), ACTIONS),
                rs.getBoolean("enabled"),
                rs.getInt("position"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getObject("updated_by", UUID.class));
    }

    static OffsetDateTime now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
