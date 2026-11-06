package ru.easyitsm.automation.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Правило автоматизации, как его хранит и отдаёт API. {@code version} совпадает с ETag. */
record Rule(
        UUID id,
        String name,
        String description,
        String trigger,
        Conditions conditions,
        List<Action> actions,
        boolean enabled,
        int position,
        long version,
        Instant createdAt,
        Instant updatedAt,
        UUID updatedBy) {
}
