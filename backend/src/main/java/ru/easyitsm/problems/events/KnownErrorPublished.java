package ru.easyitsm.problems.events;

import java.time.Instant;
import java.util.UUID;

/** Известная ошибка опубликована и видна агентам при инцидентах (PRB-03). */
public record KnownErrorPublished(UUID tenantId, Instant occurredAt, UUID knownErrorId, UUID problemId,
                                  UUID actorId) {
}
