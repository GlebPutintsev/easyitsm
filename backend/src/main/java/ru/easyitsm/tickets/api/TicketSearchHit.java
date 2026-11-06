package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.UUID;

/** Найденная заявка. {@code snippet} размечен символами {@link TicketSearchIndex#MARK_START}/{@code MARK_END}. */
public record TicketSearchHit(
        UUID id,
        String number,
        TicketType type,
        String title,
        String statusCode,
        StatusCategory statusCategory,
        Priority priority,
        Instant updatedAt,
        String snippet,
        double rank) {
}
