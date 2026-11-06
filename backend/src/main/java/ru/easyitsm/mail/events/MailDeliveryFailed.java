package ru.easyitsm.mail.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Письмо не доставлено после всех попыток (notification.failed, раздел 6.2 ТЗ).
 * Слушатели: аудит, мониторинг. Текст письма не передаётся.
 */
public record MailDeliveryFailed(UUID tenantId, UUID deliveryId, String kind, String recipient,
                                 UUID recipientUserId, UUID ticketId, String error, Instant failedAt) {
}
