package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Общие поля событий заявки.
 * {@code occurredAt} — время бизнес-события (не публикации), {@code actorId} — null для системы и таймеров,
 * {@code ticket} — снимок заявки после изменения.
 */
public interface TicketEvent {

    UUID tenantId();

    Instant occurredAt();

    UUID actorId();

    TicketSnapshot ticket();

    default UUID ticketId() {
        return ticket().id();
    }
}
