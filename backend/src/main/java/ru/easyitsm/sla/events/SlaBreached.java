package ru.easyitsm.sla.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Срок SLA нарушен, SLA-05. Событие публикуется один раз на счётчик.
 * Слушатели: уведомления, автоматизация (эскалация, смена приоритета), отчёты.
 *
 * @param breachedAt точное время нарушения, равно сроку
 * @param actions    настраиваемые действия при нарушении, например {@code notify:group_lead},
 *                   {@code escalate:<groupId>}, {@code set_priority:P1}
 */
public record SlaBreached(
        UUID tenantId,
        UUID ticketId,
        UUID instanceId,
        UUID policyId,
        String kind,
        String metric,
        String priority,
        Instant breachedAt,
        List<String> actions) {
}
