package ru.easyitsm.sla.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Счётчик SLA по заявке на момент запроса.
 *
 * @param kind            sla, ola или uc
 * @param metric          response (реакция) или resolution (решение)
 * @param state           running, paused, completed или cancelled
 * @param dueAt           срок; {@code null}, пока счётчик на паузе
 * @param breachedAt      момент нарушения: точное время, когда истёк срок, а не время обнаружения
 * @param elapsedSeconds  затраченное рабочее время без пауз
 * @param remainingSeconds остаток рабочего времени, отрицательный после нарушения
 * @param percent         доля затраченного времени от цели
 * @param pausedSince     начало текущей паузы
 */
public record SlaInstanceView(
        UUID id,
        UUID ticketId,
        UUID policyId,
        String policyName,
        String kind,
        String metric,
        String priority,
        String state,
        long targetSeconds,
        Instant startedAt,
        Instant dueAt,
        Instant stoppedAt,
        Instant breachedAt,
        long elapsedSeconds,
        long remainingSeconds,
        int percent,
        Instant pausedSince) {

    public boolean breached() {
        return breachedAt != null;
    }
}
