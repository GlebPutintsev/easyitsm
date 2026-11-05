package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/** Сменилась группа или исполнитель. Новые значения — в снимке. OLA группы считается от этого события. */
public record TicketAssignmentChanged(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                                      UUID fromGroupId, UUID fromAssigneeId) implements TicketEvent {
}
