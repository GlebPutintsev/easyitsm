package ru.easyitsm.changes.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;

/** Периоды заморозки изменений (CHG-05). */
@Repository
class FreezeStore {

    /** @param scope {@code all} — все изменения, {@code targeted} — только по услугам и КЕ из списков */
    record Freeze(UUID id, String title, String reason, Instant startsAt, Instant endsAt, String scope,
                  List<UUID> serviceIds, List<UUID> ciIds, long version) {

        boolean covers(List<UUID> cis, List<UUID> services) {
            if ("all".equals(scope)) {
                return true;
            }
            return cis.stream().anyMatch(ciIds::contains) || services.stream().anyMatch(serviceIds::contains);
        }
    }

    private final JdbcClient jdbc;

    FreezeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Заморозки, пересекающие интервал {@code [from, to)}. */
    List<Freeze> overlapping(Instant from, Instant to) {
        return jdbc.sql("select * from change_freeze where starts_at < :to and ends_at > :from order by starts_at, id")
                .param("from", Sql.ts(from)).param("to", Sql.ts(to)).query(FreezeStore::map).list();
    }

    List<Freeze> upcoming(Instant from, int limit) {
        return jdbc.sql("select * from change_freeze where ends_at > :from order by starts_at, id limit :limit")
                .param("from", Sql.ts(from)).param("limit", limit).query(FreezeStore::map).list();
    }

    Optional<Freeze> find(UUID id) {
        return jdbc.sql("select * from change_freeze where id = :id").param("id", id).query(FreezeStore::map)
                .optional();
    }

    UUID insert(Freeze f) {
        UUID id = Ids.newId();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into change_freeze (id, tenant_id, title, reason, starts_at, ends_at, scope, service_ids, ci_ids,
                    created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :title, :reason, :starts, :ends, :scope, cast(:services as uuid[]),
                    cast(:cis as uuid[]), now(), :actor, now(), :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("title", f.title())
                .param("reason", f.reason() == null ? "" : f.reason()).param("starts", Sql.ts(f.startsAt()))
                .param("ends", Sql.ts(f.endsAt())).param("scope", f.scope())
                .param("services", Sql.array(f.serviceIds())).param("cis", Sql.array(f.ciIds()))
                .param("actor", actor).update();
        return id;
    }

    boolean update(UUID id, Freeze f, long expected) {
        return jdbc.sql("""
                update change_freeze set title = :title, reason = :reason, starts_at = :starts, ends_at = :ends,
                    scope = :scope, service_ids = cast(:services as uuid[]), ci_ids = cast(:cis as uuid[]),
                    updated_at = now(), updated_by = :actor, version = version + 1
                 where id = :id and version = :expected""")
                .param("id", id).param("title", f.title()).param("reason", f.reason() == null ? "" : f.reason())
                .param("starts", Sql.ts(f.startsAt())).param("ends", Sql.ts(f.endsAt())).param("scope", f.scope())
                .param("services", Sql.array(f.serviceIds())).param("cis", Sql.array(f.ciIds()))
                .param("actor", CurrentActor.userId().orElse(null)).param("expected", expected).update() > 0;
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from change_freeze where id = :id").param("id", id).update() > 0;
    }

    private static Freeze map(ResultSet rs, int n) throws SQLException {
        return new Freeze(Sql.uuid(rs, "id"), rs.getString("title"), rs.getString("reason"),
                Sql.instant(rs, "starts_at"), Sql.instant(rs, "ends_at"), rs.getString("scope"),
                Sql.uuids(rs, "service_ids"), Sql.uuids(rs, "ci_ids"), rs.getLong("version"));
    }
}
