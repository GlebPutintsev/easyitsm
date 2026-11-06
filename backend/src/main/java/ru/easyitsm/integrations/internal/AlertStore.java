package ru.easyitsm.integrations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/** Таблица {@code integration_alert}: активный алерт и инцидент, который его ведёт. */
@Component
class AlertStore {

    record Row(UUID id, UUID sourceId, String fingerprint, UUID ticketId, String ticketNumber, String status,
               Severity severity, String title, int occurrences, java.time.Instant firstAt, java.time.Instant lastAt,
               java.time.Instant resolvedAt) {
    }

    private final JdbcClient jdbc;

    AlertStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Блокировка отпечатка до конца транзакции: два одновременных события одного алерта не создадут два
     * инцидента. Ключ — хеш источника и отпечатка.
     */
    void lock(UUID sourceId, String fingerprint) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "integration_alert:" + sourceId + ":" + fingerprint).query((rs, n) -> 1).list();
    }

    Optional<Row> firing(UUID sourceId, String fingerprint) {
        return jdbc.sql("""
                select * from integration_alert where source_id = :s and fingerprint = :f and status = 'firing'""")
                .param("s", sourceId).param("f", fingerprint).query(this::map).optional();
    }

    UUID insert(UUID sourceId, Alert a, UUID ticketId, String ticketNumber) {
        UUID id = Ids.newId();
        var now = SourceStore.now();
        jdbc.sql("""
                insert into integration_alert (id, tenant_id, source_id, fingerprint, ticket_id, ticket_number, status,
                    severity, title, occurrences, first_at, last_at, created_at, updated_at, version)
                values (:id, :tenant, :source, :fp, :ticket, :number, 'firing', :severity, :title, 1, :now, :now,
                    :now, :now, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("source", sourceId)
                .param("fp", a.fingerprint()).param("ticket", ticketId).param("number", ticketNumber)
                .param("severity", a.severity().code()).param("title", AlertFormats.cut(a.title(), 500))
                .param("now", now)
                .update();
        return id;
    }

    void repeat(UUID id, Severity severity) {
        jdbc.sql("""
                update integration_alert set occurrences = occurrences + 1, last_at = :now, severity = :severity,
                       updated_at = :now, version = version + 1
                 where id = :id""")
                .param("now", SourceStore.now()).param("severity", severity.code()).param("id", id).update();
    }

    void resolve(UUID id) {
        jdbc.sql("""
                update integration_alert set status = 'resolved', resolved_at = :now, last_at = :now, updated_at = :now,
                       version = version + 1
                 where id = :id""")
                .param("now", SourceStore.now()).param("id", id).update();
    }

    List<Row> active(UUID sourceId, int limit) {
        return jdbc.sql("""
                select * from integration_alert where source_id = :s and status = 'firing'
                 order by last_at desc, id desc limit :limit""")
                .param("s", sourceId).param("limit", limit).query(this::map).list();
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getString("fingerprint"),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("ticket_number"),
                rs.getString("status"),
                Severity.fromCode(rs.getString("severity")),
                rs.getString("title"),
                rs.getInt("occurrences"),
                SourceStore.instant(rs, "first_at"),
                SourceStore.instant(rs, "last_at"),
                SourceStore.instant(rs, "resolved_at"));
    }
}
