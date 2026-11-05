package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.EscalationKind;
import ru.easyitsm.tickets.api.TicketSnapshot;

/** Эскалация (INC-04). Для иерархической уведомления получает руководитель группы. */
public record TicketEscalated(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                              EscalationKind kind, int level, UUID fromGroupId, String reason)
        implements TicketEvent {
}
