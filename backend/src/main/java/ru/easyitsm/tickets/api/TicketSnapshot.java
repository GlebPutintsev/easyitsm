package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Снимок заявки: единые поля всех типов без текста описания и настраиваемых полей.
 * Входит в каждое событие модуля и возвращается {@link TicketQueries}.
 *
 * @param organizationId организация заявителя, если известна (условие применения SLA-02)
 * @param catalogItemId  предложение каталога, из которого создан запрос
 * @param version        версия записи, совпадает с ETag в REST
 */
public record TicketSnapshot(
        UUID id,
        String number,
        TicketType type,
        String title,
        String statusCode,
        StatusCategory statusCategory,
        Priority priority,
        Level impact,
        Level urgency,
        UUID requesterId,
        UUID createdForId,
        UUID organizationId,
        UUID groupId,
        UUID assigneeId,
        UUID serviceId,
        UUID ciId,
        UUID categoryId,
        UUID catalogItemId,
        Channel channel,
        boolean confidential,
        UUID parentId,
        Instant dueAt,
        Instant createdAt,
        Instant firstResponseAt,
        Instant resolvedAt,
        Instant closedAt,
        long version) {
}
