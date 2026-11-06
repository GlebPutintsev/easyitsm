package ru.easyitsm.changes.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Изменение согласовано и запланировано ({@code change.approved}): все слоты маршрута утверждены, стандартное
 * изменение отправлено по шаблону или экстренное запланировано до последующего согласования.
 */
public record ChangeApproved(
        UUID tenantId,
        Instant occurredAt,
        UUID actorId,
        UUID changeId,
        String number,
        String type,
        String riskLevel,
        Instant windowStart,
        Instant windowEnd,
        List<UUID> ciIds,
        List<UUID> serviceIds) {
}
