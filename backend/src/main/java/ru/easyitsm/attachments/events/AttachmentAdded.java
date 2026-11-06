package ru.easyitsm.attachments.events;

import java.util.UUID;

/**
 * Файл прошёл проверку и доступен тем, кто видит владельца.
 *
 * @param ownerType {@code ticket} или {@code article}
 * @param visibility {@code public} или {@code internal}
 */
public record AttachmentAdded(UUID tenantId, UUID attachmentId, String ownerType, UUID ownerId, String visibility,
                              String fileName, long sizeBytes, UUID uploadedBy) {
}
