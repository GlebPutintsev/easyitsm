package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Дубликаты слиты в основную заявку {@code ticket} (TCK-13). Каждая из {@code sourceTicketIds} закрыта
 * как дубликат и дополнительно получила своё TicketStatusChanged с trigger = merge.
 */
public record TicketMerged(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                           List<UUID> sourceTicketIds) implements TicketEvent {
}
