package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Первая реакция поддержки: переход в IN_PROGRESS или первый публичный ответ агента, что раньше.
 * Публикуется один раз на заявку, время — в {@code occurredAt} и {@code ticket.firstResponseAt()}.
 *
 * @param via {@code status} или {@code reply}
 */
public record TicketFirstResponded(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                                   String via) implements TicketEvent {
}
