package ru.easyitsm.catalog.events;

import java.util.UUID;

/**
 * Услуга заказана через каталог. Если {@code createdForId} задан, заявку оформили от имени другого
 * сотрудника и его нужно уведомить (REQ-07). Слушатели: уведомления, отчёты.
 */
public record CatalogItemOrdered(UUID tenantId, UUID itemId, String itemName, UUID ticketId, String ticketNumber,
                                 UUID requesterId, UUID createdForId, boolean awaitingApproval) {
}
