package ru.easyitsm.catalog.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Всё, что каталогу нужно от модуля tickets. Реализация вызывает {@code ru.easyitsm.tickets.api}
 * в той же транзакции, поэтому заказ, заявка и согласования сохраняются атомарно.
 */
interface TicketGateway {

    TicketRef create(NewTicket ticket);

    /** Системный переход по итогам согласования: «Согласована» или «Отклонена» с причиной (5.4 ТЗ). */
    void applyApprovalOutcome(UUID ticketId, boolean approved, String reason);

    Optional<TicketInfo> find(UUID ticketId);

    /**
     * @param type             request или incident
     * @param impact           only_me, department или everyone
     * @param awaitingApproval стартовый статус «На согласовании»
     */
    record NewTicket(String type, String title, String description, UUID requesterId, UUID createdForId,
                     UUID catalogItemId, String impact, Map<String, Object> custom, boolean awaitingApproval) {
    }

    record TicketRef(UUID id, String number, String status) {
    }

    record TicketInfo(UUID id, String number, String title, String statusCategory, UUID requesterId,
                      UUID createdForId) {
    }
}
