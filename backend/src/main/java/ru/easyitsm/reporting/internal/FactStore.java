package ru.easyitsm.reporting.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.sla.api.SlaInstanceView;
import ru.easyitsm.sla.api.SlaQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Витрина отчётов: применяет факты из событий к {@code reporting_ticket_fact} и {@code reporting_sla_breach}.
 * Вызывается из слушателя событий после фиксации транзакции источника, поэтому открывает свою транзакцию.
 */
@Component
class FactStore {

    /** Счётчики SLA, по которым считается соблюдение SLA. OLA и UC в показатель не входят. */
    static final String SLA_KIND = "sla";

    private final JdbcClient jdbc;
    private final SlaQueries sla;

    FactStore(JdbcClient jdbc, SlaQueries sla) {
        this.jdbc = jdbc;
        this.sla = sla;
    }

    /**
     * Снимок заявки после изменения. Более старый снимок (версия меньше сохранённой) не перезаписывает строку:
     * события могут прийти не по порядку при повторной доставке из outbox.
     *
     * Счётчики SLA читаются через {@link SlaQueries}: так видно, есть ли у заявки SLA, даже если она не нарушена.
     *
     * @param reopened заявку вернули из «Решена» в работу этим событием
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyTicket(TicketSnapshot t, Instant occurredAt, boolean reopened) {
        UUID tenant = TenantContext.require();
        jdbc.sql("""
                insert into reporting_ticket_fact (ticket_id, tenant_id, number, type, title, status_code,
                    status_category, priority, channel, requester_id, group_id, assignee_id, service_id, category_id,
                    confidential, due_at, created_at, first_response_at, resolved_at, closed_at, reopened_count,
                    ticket_version, updated_at)
                values (:id, :tenant, :number, :type, :title, :statusCode, :category, :priority, :channel,
                    :requester, :group, :assignee, :service, :categoryId, :confidential, :dueAt, :createdAt,
                    :firstResponseAt, :resolvedAt, :closedAt, :reopened, :version, :now)
                on conflict (ticket_id) do update set
                    number = excluded.number, type = excluded.type, title = excluded.title,
                    status_code = excluded.status_code, status_category = excluded.status_category,
                    priority = excluded.priority, channel = excluded.channel, requester_id = excluded.requester_id,
                    group_id = excluded.group_id, assignee_id = excluded.assignee_id,
                    service_id = excluded.service_id, category_id = excluded.category_id,
                    confidential = excluded.confidential,
                    due_at = excluded.due_at, created_at = excluded.created_at,
                    first_response_at = excluded.first_response_at, resolved_at = excluded.resolved_at,
                    closed_at = excluded.closed_at, ticket_version = excluded.ticket_version,
                    reopened_count = reporting_ticket_fact.reopened_count + excluded.reopened_count,
                    updated_at = excluded.updated_at
                where reporting_ticket_fact.ticket_version <= excluded.ticket_version""")
                .param("id", t.id())
                .param("tenant", tenant)
                .param("number", t.number())
                .param("type", t.type() == null ? "incident" : t.type().code())
                .param("title", t.title() == null ? "" : t.title())
                .param("statusCode", t.statusCode())
                .param("category", t.statusCategory().code())
                .param("priority", t.priority() == null ? "P4" : t.priority().code())
                .param("channel", t.channel() == null ? null : t.channel().code())
                .param("requester", t.createdForId() != null ? t.createdForId() : t.requesterId())
                .param("group", t.groupId())
                .param("assignee", t.assigneeId())
                .param("service", t.serviceId() != null ? t.serviceId() : t.catalogItemId())
                .param("categoryId", t.categoryId())
                .param("confidential", t.confidential())
                .param("dueAt", ts(t.dueAt()))
                .param("createdAt", ts(t.createdAt()))
                .param("firstResponseAt", ts(t.firstResponseAt()))
                .param("resolvedAt", ts(t.resolvedAt()))
                .param("closedAt", ts(t.closedAt()))
                .param("reopened", reopened ? 1 : 0)
                .param("version", t.version())
                .param("now", ts(occurredAt == null ? Instant.now() : occurredAt))
                .update();
        for (SlaInstanceView v : sla.forTicket(t.id())) {
            if (!SLA_KIND.equals(v.kind())) {
                continue;
            }
            markSla(t.id(), v.metric(), v.breachedAt());
        }
    }

    /** Нарушение SLA (событие модуля sla). Повторная доставка того же счётчика ничего не меняет. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyBreach(UUID instanceId, UUID ticketId, String kind, String metric, String priority,
                            Instant breachedAt) {
        jdbc.sql("""
                insert into reporting_sla_breach (instance_id, tenant_id, ticket_id, kind, metric, priority, breached_at)
                values (:instance, :tenant, :ticket, :kind, :metric, :priority, :at)
                on conflict (instance_id) do nothing""")
                .param("instance", instanceId)
                .param("tenant", TenantContext.require())
                .param("ticket", ticketId)
                .param("kind", kind)
                .param("metric", metric)
                .param("priority", priority)
                .param("at", ts(breachedAt))
                .update();
        if (SLA_KIND.equals(kind)) {
            markSla(ticketId, metric, breachedAt);
        }
    }

    /** Оценка заявителя (событие модуля surveys). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applySurvey(UUID ticketId, int score, Instant at) {
        jdbc.sql("update reporting_ticket_fact set csat_score = :score, csat_at = :at where ticket_id = :id")
                .param("score", score)
                .param("at", ts(at))
                .param("id", ticketId)
                .update();
    }

    /** Отмечает, что у заявки есть SLA, и первое нарушение по метрике (response или resolution). */
    private void markSla(UUID ticketId, String metric, Instant breachedAt) {
        String column = "response".equals(metric) ? "response_breached_at"
                : "resolution".equals(metric) ? "resolution_breached_at" : null;
        if (column == null || breachedAt == null) {
            jdbc.sql("update reporting_ticket_fact set has_sla = true where ticket_id = :id and not has_sla")
                    .param("id", ticketId).update();
            return;
        }
        jdbc.sql("update reporting_ticket_fact set has_sla = true, " + column + " = least(coalesce(" + column
                        + ", :at), :at) where ticket_id = :id")
                .param("at", ts(breachedAt))
                .param("id", ticketId)
                .update();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
