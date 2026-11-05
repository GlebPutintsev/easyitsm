package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Ответ или внутренняя заметка в ленте. Текст не передаётся (может быть конфиденциальным), его читают по
 * {@code activityId} через REST с проверкой прав.
 *
 * @param fromRequester автор — заявитель или сотрудник, для которого создана заявка
 * @param mentionedUserIds упомянутые через @ пользователи (TCK-10), они же добавлены в наблюдатели
 */
public record TicketCommentAdded(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                                 UUID activityId, Visibility visibility, boolean fromRequester,
                                 List<UUID> mentionedUserIds) implements TicketEvent {
}
