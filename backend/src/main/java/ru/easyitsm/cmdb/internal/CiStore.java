package ru.easyitsm.cmdb.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.cmdb.api.CiCriticality;
import ru.easyitsm.cmdb.api.CiLayer;
import ru.easyitsm.cmdb.api.CiStatus;
import ru.easyitsm.platform.tenant.TenantContext;

/** Доступ к таблицам {@code ci} и {@code ci_history}. RLS ограничивает строки арендатором. */
@Component
class CiStore {

    static final String SELECT = """
            select c.id, c.class_id, k.code as class_code, k.name as class_name, k.layer, c.name, c.status,
                   c.environment, c.criticality, c.owner_group_id, c.description, c.attributes::text as attributes,
                   c.attribute_sources::text as attribute_sources, c.last_seen_at, c.created_at, c.created_by,
                   c.updated_at, c.updated_by, c.version
              from ci c join ci_class k on k.id = c.class_id
            """;

    private final JdbcClient jdbc;
    private final CmdbJson json;

    CiStore(JdbcClient jdbc, CmdbJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<CiRow> find(UUID id) {
        return jdbc.sql(SELECT + " where c.id = :id").param("id", id).query(this::map).optional();
    }

    Optional<CiRow> lock(UUID id) {
        return jdbc.sql(SELECT + " where c.id = :id for update of c").param("id", id).query(this::map).optional();
    }

    /** В порядке {@code ids}, отсутствующие пропускаются. */
    List<CiRow> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, CiRow> found = new LinkedHashMap<>();
        jdbc.sql(SELECT + " where c.id = any(:ids)").param("ids", ids.stream().distinct().toArray(UUID[]::new))
                .query(this::map).list().forEach(r -> found.put(r.id(), r));
        List<CiRow> out = new ArrayList<>(found.size());
        for (UUID id : ids) {
            CiRow r = found.remove(id);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    List<CiRow> query(String whereAndOrder, Map<String, ?> params) {
        return jdbc.sql(SELECT + whereAndOrder).params(params).query(this::map).list();
    }

    void insert(CiRow r) {
        jdbc.sql("""
                insert into ci (id, tenant_id, class_id, name, status, environment, criticality, owner_group_id,
                                description, attributes, attribute_sources, last_seen_at, created_at, created_by,
                                updated_at, updated_by, version)
                values (:id, :tenant, :classId, :name, :status, :environment, :criticality, :owner, :description,
                        cast(:attributes as jsonb), cast(:sources as jsonb), :lastSeen, :createdAt, :createdBy,
                        :updatedAt, :updatedBy, :version)""")
                .param("id", r.id()).param("tenant", TenantContext.require()).param("classId", r.classId())
                .param("name", r.name()).param("status", r.status().code()).param("environment", r.environment())
                .param("criticality", r.criticality().code()).param("owner", r.ownerGroupId())
                .param("description", r.description()).param("attributes", json.write(r.attributes()))
                .param("sources", json.write(r.attributeSources())).param("lastSeen", Sql.ts(r.lastSeenAt()))
                .param("createdAt", Sql.ts(r.createdAt())).param("createdBy", r.createdBy())
                .param("updatedAt", Sql.ts(r.updatedAt())).param("updatedBy", r.updatedBy())
                .param("version", r.version())
                .update();
    }

    /** Обновление с проверкой версии: {@code r.version()} уже новая, в БД ожидается на единицу меньше. */
    void update(CiRow r) {
        int n = jdbc.sql("""
                update ci set class_id = :classId, name = :name, status = :status, environment = :environment,
                              criticality = :criticality, owner_group_id = :owner, description = :description,
                              attributes = cast(:attributes as jsonb), attribute_sources = cast(:sources as jsonb),
                              last_seen_at = :lastSeen, updated_at = :updatedAt, updated_by = :updatedBy,
                              version = :version
                 where id = :id and version = :expected""")
                .param("id", r.id()).param("classId", r.classId()).param("name", r.name())
                .param("status", r.status().code()).param("environment", r.environment())
                .param("criticality", r.criticality().code()).param("owner", r.ownerGroupId())
                .param("description", r.description()).param("attributes", json.write(r.attributes()))
                .param("sources", json.write(r.attributeSources())).param("lastSeen", Sql.ts(r.lastSeenAt()))
                .param("updatedAt", Sql.ts(r.updatedAt())).param("updatedBy", r.updatedBy())
                .param("version", r.version()).param("expected", r.version() - 1)
                .update();
        if (n != 1) {
            throw new IllegalStateException("КЕ " + r.id() + " изменена параллельно");
        }
    }

    void history(UUID ciId, String field, Object oldValue, Object newValue, String source, UUID actorId,
                 java.time.Instant at) {
        jdbc.sql("""
                insert into ci_history (id, tenant_id, ci_id, field, old_value, new_value, source, actor_id, at)
                values (:id, :tenant, :ci, :field, cast(:old as jsonb), cast(:new as jsonb), :source, :actor, :at)""")
                .param("id", ru.easyitsm.platform.id.Ids.newId()).param("tenant", TenantContext.require())
                .param("ci", ciId).param("field", field).param("old", json.write(oldValue))
                .param("new", json.write(newValue)).param("source", source).param("actor", actorId)
                .param("at", Sql.ts(at))
                .update();
    }

    private CiRow map(ResultSet rs, int n) throws SQLException {
        return new CiRow(Sql.uuid(rs, "id"), Sql.uuid(rs, "class_id"), rs.getString("class_code"),
                rs.getString("class_name"), CiLayer.fromCode(rs.getString("layer")), rs.getString("name"),
                CiStatus.fromCode(rs.getString("status")), rs.getString("environment"),
                CiCriticality.fromCode(rs.getString("criticality")), Sql.uuid(rs, "owner_group_id"),
                rs.getString("description"), json.readMap(rs.getString("attributes")),
                json.readMap(rs.getString("attribute_sources")), Sql.instant(rs, "last_seen_at"),
                Sql.instant(rs, "created_at"), Sql.uuid(rs, "created_by"), Sql.instant(rs, "updated_at"),
                Sql.uuid(rs, "updated_by"), rs.getLong("version"));
    }
}
