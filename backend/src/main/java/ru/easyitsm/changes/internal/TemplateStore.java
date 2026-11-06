package ru.easyitsm.changes.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;

/** Шаблоны изменений (CHG-08). */
@Repository("changeTemplateStore")
class TemplateStore {

    /**
     * @param content тексты: goal, justification, impact, risks, downtimeMinutes, durationMinutes, systemCriticality
     * @param steps   шаги плана в формате {@link ChangeDtos.StepInput}
     */
    record Template(UUID id, String name, String description, String changeType, boolean preapproved,
                    String riskLevel, String pipelineKey, Map<String, Object> content, List<Map<String, Object>> steps,
                    List<UUID> ciIds, List<UUID> serviceIds, boolean active, Instant updatedAt, long version) {
    }

    private final JdbcClient jdbc;
    private final Json json;

    TemplateStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<Template> list(boolean onlyActive) {
        return jdbc.sql("select * , content::text as content_json, steps::text as steps_json from change_template"
                        + (onlyActive ? " where active" : "") + " order by name, id")
                .query(this::map).list();
    }

    Optional<Template> find(UUID id) {
        return jdbc.sql("select *, content::text as content_json, steps::text as steps_json from change_template where id = :id")
                .param("id", id).query(this::map).optional();
    }

    Optional<Template> byPipelineKey(String key) {
        return jdbc.sql("""
                select *, content::text as content_json, steps::text as steps_json from change_template
                 where pipeline_key = :key and active""")
                .param("key", key).query(this::map).optional();
    }

    UUID insert(Template t) {
        UUID id = Ids.newId();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into change_template (id, tenant_id, name, description, change_type, preapproved, risk_level,
                    pipeline_key, content, steps, ci_ids, service_ids, active, created_at, created_by, updated_at,
                    updated_by, version)
                values (:id, :tenant, :name, :description, :type, :preapproved, :risk, :key, cast(:content as jsonb),
                    cast(:steps as jsonb), cast(:cis as uuid[]), cast(:services as uuid[]), :active, now(), :actor,
                    now(), :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).params(params(t)).param("actor", actor)
                .update();
        return id;
    }

    /** @return false — версия не совпала */
    boolean update(UUID id, Template t, long expected) {
        return jdbc.sql("""
                update change_template set name = :name, description = :description, change_type = :type,
                    preapproved = :preapproved, risk_level = :risk, pipeline_key = :key,
                    content = cast(:content as jsonb), steps = cast(:steps as jsonb), ci_ids = cast(:cis as uuid[]),
                    service_ids = cast(:services as uuid[]), active = :active, updated_at = now(),
                    updated_by = :actor, version = version + 1
                 where id = :id and version = :expected""")
                .param("id", id).params(params(t)).param("actor", CurrentActor.userId().orElse(null))
                .param("expected", expected).update() > 0;
    }

    private Map<String, Object> params(Template t) {
        var p = new java.util.LinkedHashMap<String, Object>();
        p.put("name", t.name());
        p.put("description", t.description() == null ? "" : t.description());
        p.put("type", t.changeType());
        p.put("preapproved", t.preapproved());
        p.put("risk", t.riskLevel());
        p.put("key", t.pipelineKey());
        p.put("content", json.write(t.content() == null ? Map.of() : t.content()));
        p.put("steps", json.write(t.steps() == null ? List.of() : t.steps()));
        p.put("cis", Sql.array(t.ciIds()));
        p.put("services", Sql.array(t.serviceIds()));
        p.put("active", t.active());
        return p;
    }

    private Template map(ResultSet rs, int n) throws SQLException {
        return new Template(Sql.uuid(rs, "id"), rs.getString("name"), rs.getString("description"),
                rs.getString("change_type"), rs.getBoolean("preapproved"), rs.getString("risk_level"),
                rs.getString("pipeline_key"), json.map(rs.getString("content_json")),
                json.list(rs.getString("steps_json")), Sql.uuids(rs, "ci_ids"), Sql.uuids(rs, "service_ids"),
                rs.getBoolean("active"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
