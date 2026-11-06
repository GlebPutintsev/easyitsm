package ru.easyitsm.mim.events;

import java.time.Instant;
import java.util.UUID;

/** Командный центр закрыт, черновик разбора готов (MIM-05). problemId — созданная проблема или null. */
public record MajorIncidentClosed(UUID tenantId, Instant occurredAt, UUID mimId, UUID ticketId, Instant declaredAt,
                                  Instant restoredAt, UUID problemId, UUID actorId) {
}
