package ru.easyitsm.catalog.events;

import java.time.Instant;
import java.util.UUID;

/** Согласующему назначено решение (approval.requested). Слушатели: уведомления, SLA согласования. */
public record ApprovalRequested(UUID tenantId, UUID approvalId, UUID ticketId, String ticketNumber, String title,
                                UUID approverId, String stepName, Instant dueAt) {
}
