package ru.easyitsm.tickets.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.events.TicketWatcherAdded;

/** Наблюдатели заявки (TCK-10). */
@Component
class Watchers {

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    Watchers(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    record Watcher(UUID userId, String reason, Instant createdAt) {
    }

    List<Watcher> list(UUID ticketId) {
        return jdbc.sql("select user_id, reason, created_at from ticket_watcher where ticket_id = :t order by created_at, id")
                .param("t", ticketId)
                .query((rs, n) -> new Watcher(Sql.uuid(rs, "user_id"), rs.getString("reason"), Sql.instant(rs, "created_at")))
                .list();
    }

    List<UUID> userIds(UUID ticketId) {
        return list(ticketId).stream().map(Watcher::userId).toList();
    }

    /** Подписывает пользователя. Повтор ничего не делает. @return true — добавлен впервые */
    boolean add(TicketRow t, UUID userId, String reason, Actor actor) {
        Instant now = Sql.now();
        int n = jdbc.sql("""
                insert into ticket_watcher (id, tenant_id, ticket_id, user_id, reason, created_at, created_by,
                    updated_at, updated_by, version)
                values (:id, :tenant, :t, :u, :reason, :now, :actor, :now, :actor, 0)
                on conflict (ticket_id, user_id) do nothing""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("t", t.id)
                .param("u", userId).param("reason", reason).param("now", Sql.ts(now)).param("actor", actor.userId())
                .update();
        if (n == 1) {
            events.publishEvent(new TicketWatcherAdded(t.tenantId, now, actor.userId(), t.snapshot(), userId, reason));
        }
        return n == 1;
    }

    boolean remove(UUID ticketId, UUID userId) {
        return jdbc.sql("delete from ticket_watcher where ticket_id = :t and user_id = :u")
                .param("t", ticketId).param("u", userId).update() == 1;
    }
}
