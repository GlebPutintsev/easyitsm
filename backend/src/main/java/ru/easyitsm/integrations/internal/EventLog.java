package ru.easyitsm.integrations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** Таблица {@code integration_event_log}: итог каждого события для экрана «Интеграции → Мониторинг». */
@Component
class EventLog {

    /** Запись журнала. {@code alertStatus}, {@code fingerprint} — null, если тело не разобрано. */
    record Entry(UUID id, UUID sourceId, Instant receivedAt, String fingerprint, String alertStatus, String severity,
                 String title, String outcome, UUID ticketId, String ticketNumber, String detail) {
    }

    private final JdbcClient jdbc;

    EventLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void write(UUID sourceId, Alert alert, String outcome, UUID ticketId, String ticketNumber, String detail) {
        var now = SourceStore.now();
        jdbc.sql("""
                insert into integration_event_log (id, tenant_id, source_id, received_at, fingerprint, alert_status,
                    severity, title, outcome, ticket_id, ticket_number, detail, created_at, updated_at, version)
                values (:id, :tenant, :source, :now, :fp, :status, :severity, :title, :outcome, :ticket, :number,
                    :detail, :now, :now, 0)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("source", sourceId)
                .param("now", now)
                .param("fp", alert == null ? null : AlertFormats.cut(alert.fingerprint(), 500))
                .param("status", alert == null ? null : alert.status())
                .param("severity", alert == null ? null : alert.severity().code())
                .param("title", alert == null ? null : AlertFormats.cut(alert.title(), 500))
                .param("outcome", outcome).param("ticket", ticketId).param("number", ticketNumber)
                .param("detail", AlertFormats.cut(detail, 1000))
                .update();
    }

    /** Новые сверху, курсор по (received_at, id). */
    CursorPage<Entry> page(UUID sourceId, String outcome, PageParams page) {
        KeysetCursor after = page.keyset();
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (sourceId != null) {
            where.add("source_id = :source");
            params.put("source", sourceId);
        }
        if (outcome != null && !outcome.isBlank()) {
            where.add("outcome = :outcome");
            params.put("outcome", outcome);
        }
        if (after != null) {
            where.add("(received_at, id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid))");
            params.put("afterAt", Instant.parse(after.sortValue()).atOffset(ZoneOffset.UTC));
            params.put("afterId", after.id());
        }
        params.put("limit", page.limit() + 1);
        String sql = "select * from integration_event_log" + (where.isEmpty() ? "" : " where " + String.join(" and ", where))
                + " order by received_at desc, id desc limit :limit";
        List<Entry> rows = jdbc.sql(sql).params(params).query(this::map).list();
        return CursorPage.of(rows, page.limit(), e -> new KeysetCursor(e.receivedAt().toString(), e.id()));
    }

    int purge(int keepDays) {
        Integer n = jdbc.sql("select integration_purge_events(:d)").param("d", keepDays).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private Entry map(ResultSet rs, int n) throws SQLException {
        return new Entry(
                rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class),
                SourceStore.instant(rs, "received_at"),
                rs.getString("fingerprint"),
                rs.getString("alert_status"),
                rs.getString("severity"),
                rs.getString("title"),
                rs.getString("outcome"),
                rs.getObject("ticket_id", UUID.class),
                rs.getString("ticket_number"),
                rs.getString("detail"));
    }
}
