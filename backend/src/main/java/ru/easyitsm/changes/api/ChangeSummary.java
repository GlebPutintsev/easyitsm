package ru.easyitsm.changes.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Сводка изменения для других модулей и списков. */
public record ChangeSummary(
        UUID id,
        String number,
        String title,
        ChangeType type,
        ChangeState state,
        RiskLevel riskLevel,
        Instant windowStart,
        Instant windowEnd,
        List<UUID> ciIds,
        List<UUID> serviceIds,
        ChangeOutcome outcome) {
}
