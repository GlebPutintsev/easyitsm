package ru.easyitsm.assets.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;

/** Использование лицензий ({@code license_assignment}): кем или чем занята лицензия. Под RLS арендатора. */
@Component
class LicenseAssignments {

    private final JdbcClient jdbc;

    LicenseAssignments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Row(UUID id, UUID licenseId, UUID assetId, String assetTag, String assetName, UUID userId, UUID assignedBy,
               Instant assignedAt) {
    }

    private static final String SELECT = """
            select la.id, la.license_id, la.asset_id, a.tag, a.name, la.user_id, la.assigned_by, la.assigned_at
              from license_assignment la left join asset a on a.id = la.asset_id""";

    Map<UUID, Long> usage(Collection<UUID> licenseIds) {
        Map<UUID, Long> out = new HashMap<>();
        if (licenseIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select license_id, count(*) from license_assignment where license_id in (:ids) group by license_id")
                .param("ids", licenseIds)
                .query(rs -> {
                    out.put(rs.getObject(1, UUID.class), rs.getLong(2));
                });
        return out;
    }

    long used(UUID licenseId) {
        return jdbc.sql("select count(*) from license_assignment where license_id = :id").param("id", licenseId)
                .query(Long.class).single();
    }

    List<Row> ofLicense(UUID licenseId) {
        return jdbc.sql(SELECT + " where la.license_id = :id order by la.assigned_at desc, la.id desc")
                .param("id", licenseId).query(LicenseAssignments::row).list();
    }

    List<Row> ofAsset(UUID assetId) {
        return jdbc.sql(SELECT + " where la.asset_id = :id order by la.assigned_at desc").param("id", assetId)
                .query(LicenseAssignments::row).list();
    }

    List<Row> ofUser(UUID userId) {
        return jdbc.sql(SELECT + " where la.user_id = :id order by la.assigned_at desc").param("id", userId)
                .query(LicenseAssignments::row).list();
    }

    /** @return id новой записи или null, если лицензия уже занята этим устройством или сотрудником */
    UUID add(UUID licenseId, UUID assetId, UUID userId, Instant at) {
        UUID id = Ids.newId();
        int n = jdbc.sql("""
                        insert into license_assignment (id, tenant_id, license_id, asset_id, user_id, assigned_by,
                                                        assigned_at)
                        values (:id, app_current_tenant(), :license, :asset, :user, :actor, :at)
                        on conflict do nothing""")
                .param("id", id).param("license", licenseId).param("asset", assetId).param("user", userId)
                .param("actor", CurrentActor.userId().orElse(null)).param("at", Timestamp.from(at))
                .update();
        return n > 0 ? id : null;
    }

    boolean remove(UUID licenseId, UUID assignmentId) {
        return jdbc.sql("delete from license_assignment where id = :id and license_id = :license")
                .param("id", assignmentId).param("license", licenseId).update() > 0;
    }

    /** Списанное устройство освобождает свои лицензии. */
    int releaseAsset(UUID assetId) {
        return jdbc.sql("delete from license_assignment where asset_id = :id").param("id", assetId).update();
    }

    private static Row row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                rs.getTimestamp(8).toInstant());
    }
}
