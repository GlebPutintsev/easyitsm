package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Смена приоритета (пересчёт SLA-06). Новый приоритет — в {@code ticket.priority()}.
 *
 * @param manual true — задан вручную в обход матрицы, тогда {@code reason} обязательна (раздел 5.2)
 */
public record TicketPriorityChanged(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                                    Priority fromPriority, boolean manual, String reason) implements TicketEvent {
}
