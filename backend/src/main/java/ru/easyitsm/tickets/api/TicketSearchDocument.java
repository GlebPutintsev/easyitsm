package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Заявка для поискового индекса: текст и атрибуты доступа.
 *
 * @param publicText     публичные ответы ленты, видны всем, кто видит заявку
 * @param internalText   внутренние заметки, ищутся только поддержкой ({@link TicketReadScope#seesInternal()})
 * @param participantIds заявитель, получатель, исполнитель, наблюдатели и кто зарегистрировал: видят заявку всегда
 */
public record TicketSearchDocument(
        UUID id,
        String number,
        TicketType type,
        String title,
        String description,
        String publicText,
        String internalText,
        String statusCode,
        StatusCategory statusCategory,
        Priority priority,
        Instant createdAt,
        Instant updatedAt,
        Set<UUID> participantIds,
        UUID groupId,
        UUID securityGroupId,
        boolean confidential,
        UUID organizationId) {

    public TicketSearchDocument {
        participantIds = participantIds == null ? Set.of() : Set.copyOf(participantIds);
    }
}
