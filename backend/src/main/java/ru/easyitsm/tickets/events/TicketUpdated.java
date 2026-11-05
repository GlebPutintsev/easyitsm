package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Изменены прочие поля, для которых нет отдельного события: title, description, service, ci, category,
 * confidential, due_at, custom.&lt;код&gt;, organization. Статус, приоритет и назначение сюда не входят.
 */
public record TicketUpdated(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                            Set<String> fields) implements TicketEvent {
}
