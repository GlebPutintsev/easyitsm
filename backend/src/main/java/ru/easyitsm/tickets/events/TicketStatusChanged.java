package ru.easyitsm.tickets.events;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Смена статуса. Новый статус — в {@code ticket.statusCode()/statusCategory()}.
 *
 * @param reasonCode код причины: для PENDING — причина ожидания (журнал пауз SLA-04), для CANCELLED — причина
 *                   отмены, для возврата из RESOLVED — {@code reopened}
 * @param reasonText текст причины или комментарий перехода
 * @param trigger    что вызвало переход: manual, group_assigned, requester_reply, timer, approval, merge, bulk, undo,
 *                   automation (правило автоматизации)
 */
public record TicketStatusChanged(UUID tenantId, Instant occurredAt, UUID actorId, TicketSnapshot ticket,
                                  String fromStatusCode, StatusCategory fromCategory,
                                  String reasonCode, String reasonText, String trigger) implements TicketEvent {
}
