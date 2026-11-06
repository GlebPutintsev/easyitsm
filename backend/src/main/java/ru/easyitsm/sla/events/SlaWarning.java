package ru.easyitsm.sla.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Счётчик достиг порога предупреждения (по умолчанию 50, 75 и 90%), SLA-05.
 * Слушатели: уведомления, автоматизация, рабочее место агента.
 *
 * @param reachedAt точное время достижения порога
 * @param actions   действия из настройки цели, например {@code notify:assignee}, {@code escalate:<groupId>}
 */
public record SlaWarning(
        UUID tenantId,
        UUID ticketId,
        UUID instanceId,
        UUID policyId,
        String kind,
        String metric,
        int percent,
        Instant reachedAt,
        Instant dueAt,
        List<String> actions) {
}
