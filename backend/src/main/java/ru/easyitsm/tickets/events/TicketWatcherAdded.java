package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/** Пользователь подписан на заявку (сам, упоминанием или при слиянии дубликатов). */
public record TicketWatcherAdded(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                                 UUID userId, String reason) implements TicketEvent {
}
