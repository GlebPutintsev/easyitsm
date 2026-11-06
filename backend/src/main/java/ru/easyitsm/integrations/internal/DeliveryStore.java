package ru.easyitsm.integrations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

/** Таблица {@code integration_webhook_delivery}: очередь и журнал доставок. */
@Component
class DeliveryStore {

    /** Доставка без тела (тело — в {@link #payload}). */
    record Delivery(UUID id, UUID webhookId, String eventType, String status, int attempts, Instant nextAttemptAt,
                    Integer lastStatus, String lastError, Instant deliveredAt, String source, String ticketNumber,
                    Instant createdAt) {
    }

    /** Запись, взятая на отправку. */
    record Due(UUID id, UUID webhookId, String eventType, String payload, int attempts) {
    }

    private static final String COLUMNS = """
            id, webhook_id, event_type, status, attempts, next_attempt_at, last_status, last_error, delivered_at, source,
            ticket_number, created_at""";

    private final JdbcClient jdbc;

    DeliveryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, UUID webhookId, String eventType, String payload, String source, String ticketNumber) {
        var now = SourceStore.now();
        jdbc.sql("""
                insert into integration_webhook_delivery (id, tenant_id, webhook_id, event_type, payload, status,
                    attempts, next_attempt_at, source, ticket_number, created_at, updated_at, version)
                values (:id, :tenant, :hook, :type, :payload, 'pending', 0, :now, :source, :number, :now, :now, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("hook", webhookId)
                .param("type", eventType).param("payload", payload)
                .param("source", AlertFormats.cut(source, 300)).param("number", ticketNumber).param("now", now)
                .update();
    }

    /** Все арендаторы: доставки, у которых наступил срок (функция SECURITY DEFINER). */
    List<SourceStore.TokenMatch> due(int limit) {
        return jdbc.sql("select tenant_id, id from integration_webhook_due(:l)").param("l", limit)
                .query((rs, n) -> new SourceStore.TokenMatch(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class)))
                .list();
    }

    /** Берёт доставку на отправку; другой под уже отправляет её или срок не наступил — пусто. */
    Optional<Due> lock(UUID id) {
        return jdbc.sql("""
                select id, webhook_id, event_type, payload, attempts from integration_webhook_delivery
                 where id = :id and status = 'pending' and next_attempt_at <= now()
                 for update skip locked""")
                .param("id", id)
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getObject("webhook_id", UUID.class),
                        rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts")))
                .optional();
    }

    void delivered(UUID id, int attempts, int status) {
        jdbc.sql("""
                update integration_webhook_delivery set status = 'delivered', attempts = :a, last_status = :s,
                       last_error = null, delivered_at = now(), next_attempt_at = null, updated_at = now(),
                       version = version + 1
                 where id = :id""")
                .param("a", attempts).param("s", status).param("id", id).update();
    }

    /** @param next null — попытки кончились, доставка уходит в неудачные */
    void failed(UUID id, int attempts, Integer status, String error, Instant next) {
        jdbc.sql("""
                update integration_webhook_delivery set status = :st, attempts = :a, last_status = :s, last_error = :e,
                       next_attempt_at = :next, updated_at = now(), version = version + 1
                 where id = :id""")
                .param("st", next == null ? "dead" : "pending").param("a", attempts).param("s", status)
                .param("e", AlertFormats.cut(error, 1000))
                .param("next", next == null ? null : next.atOffset(ZoneOffset.UTC)).param("id", id).update();
    }

    /** Ручной повтор неудачной доставки: счётчик попыток начинается заново. */
    boolean retry(UUID id) {
        return jdbc.sql("""
                update integration_webhook_delivery set status = 'pending', attempts = 0, next_attempt_at = now(),
                       updated_at = now(), version = version + 1
                 where id = :id and status = 'dead'""")
                .param("id", id).update() == 1;
    }

    Optional<Delivery> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from integration_webhook_delivery where id = :id").param("id", id)
                .query(this::map).optional();
    }

    Optional<String> payload(UUID id) {
        return jdbc.sql("select payload from integration_webhook_delivery where id = :id").param("id", id)
                .query(String.class).optional();
    }

    CursorPage<Delivery> page(UUID webhookId, String status, PageParams page) {
        KeysetCursor after = page.keyset();
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (webhookId != null) {
            where.add("webhook_id = :hook");
            params.put("hook", webhookId);
        }
        if (status != null && !status.isBlank()) {
            where.add("status = :status");
            params.put("status", status);
        }
        if (after != null) {
            where.add("(created_at, id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid))");
            params.put("afterAt", Instant.parse(after.sortValue()).atOffset(ZoneOffset.UTC));
            params.put("afterId", after.id());
        }
        params.put("limit", page.limit() + 1);
        String sql = "select " + COLUMNS + " from integration_webhook_delivery"
                + (where.isEmpty() ? "" : " where " + String.join(" and ", where))
                + " order by created_at desc, id desc limit :limit";
        List<Delivery> rows = jdbc.sql(sql).params(params).query(this::map).list();
        return CursorPage.of(rows, page.limit(), d -> new KeysetCursor(d.createdAt().toString(), d.id()));
    }

    private Delivery map(ResultSet rs, int n) throws SQLException {
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("webhook_id", UUID.class),
                rs.getString("event_type"), rs.getString("status"), rs.getInt("attempts"),
                SourceStore.instant(rs, "next_attempt_at"), rs.getObject("last_status", Integer.class), rs.getString("last_error"),
                SourceStore.instant(rs, "delivered_at"), rs.getString("source"), rs.getString("ticket_number"),
                SourceStore.instant(rs, "created_at"));
    }
}
