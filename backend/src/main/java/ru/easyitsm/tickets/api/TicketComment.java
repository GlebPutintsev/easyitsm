package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Ответ или заметка ленты для других модулей. Текст — Markdown без HTML, секреты в нём уже замаскированы.
 *
 * @param authorId null — системная запись
 */
public record TicketComment(UUID id, UUID ticketId, UUID authorId, Visibility visibility, boolean fromRequester,
                            String body, Instant createdAt) {
}
