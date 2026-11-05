package ru.easyitsm.catalog.events;

import java.util.UUID;

/** Согласующий запросил уточнение у автора, маршрут не отменяется (10.8). */
public record ApprovalInfoRequested(UUID tenantId, UUID approvalId, UUID ticketId, String ticketNumber,
                                    UUID approverId, UUID requesterId, String question) {
}
