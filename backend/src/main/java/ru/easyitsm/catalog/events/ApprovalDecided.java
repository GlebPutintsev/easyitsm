package ru.easyitsm.catalog.events;

import java.util.UUID;

/**
 * Маршрут согласования завершён (approval.decided): {@code outcome} = approved или rejected.
 * При отклонении {@code reason} содержит причину, заявитель должен быть уведомлён (5.4 ТЗ).
 */
public record ApprovalDecided(UUID tenantId, UUID ticketId, String ticketNumber, String outcome, UUID decidedBy,
                              UUID requesterId, String reason) {
}
