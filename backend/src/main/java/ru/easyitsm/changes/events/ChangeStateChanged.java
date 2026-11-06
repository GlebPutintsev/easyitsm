package ru.easyitsm.changes.events;

import java.time.Instant;
import java.util.UUID;

/** Любая смена статуса изменения: для уведомлений, автоматизации и отчётов. */
public record ChangeStateChanged(
        UUID tenantId,
        Instant occurredAt,
        UUID actorId,
        UUID changeId,
        String number,
        String fromState,
        String toState) {
}
