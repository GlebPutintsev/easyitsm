package ru.easyitsm.sla.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/**
 * Журнал счётчика: старт, пауза, возобновление, пересчёт (SLA-06), предупреждение, нарушение, завершение.
 * Записи только добавляются.
 */
@Table("sla_instance_event")
class SlaInstanceEvent extends TenantEntity {

    private UUID instanceId;
    private UUID ticketId;
    private String type;
    private Instant occurredAt;
    private String reason;
    private String fromValue;
    private String toValue;
    private Instant oldDueAt;
    private Instant newDueAt;

    protected SlaInstanceEvent() {
    }

    SlaInstanceEvent(UUID instanceId, UUID ticketId, String type, Instant occurredAt, String reason,
                     String fromValue, String toValue, Instant oldDueAt, Instant newDueAt) {
        this.instanceId = instanceId;
        this.ticketId = ticketId;
        this.type = type;
        this.occurredAt = occurredAt;
        this.reason = reason;
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.oldDueAt = oldDueAt;
        this.newDueAt = newDueAt;
    }

    String type() {
        return type;
    }

    SlaViews.HistoryItem toView() {
        return new SlaViews.HistoryItem(type, occurredAt, reason, fromValue, toValue, oldDueAt, newDueAt);
    }
}
