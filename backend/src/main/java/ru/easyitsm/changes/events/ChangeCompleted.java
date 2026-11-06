package ru.easyitsm.changes.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Работы завершены ({@code change.completed}): известен результат и фактическое окно. */
public record ChangeCompleted(
        UUID tenantId,
        Instant occurredAt,
        UUID actorId,
        UUID changeId,
        String number,
        String outcome,
        Instant actualStart,
        Instant actualEnd,
        List<UUID> ciIds,
        List<UUID> serviceIds) {
}
