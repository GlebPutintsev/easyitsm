package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/** Заявка создана. Слушатели: SLA (старт целей), уведомления, автоматизация, поиск. */
public record TicketCreated(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket)
        implements TicketEvent {
}
