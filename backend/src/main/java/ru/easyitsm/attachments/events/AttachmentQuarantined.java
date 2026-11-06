package ru.easyitsm.attachments.events;

import java.util.UUID;

/**
 * Файл не прошёл проверку и остаётся в карантине (SEC-26). Скачать его нельзя.
 *
 * @param status  {@code infected} — найден вредоносный код, {@code blocked} — запрещён политикой
 * @param verdict сигнатура антивируса или причина блокировки
 */
public record AttachmentQuarantined(UUID tenantId, UUID attachmentId, String ownerType, UUID ownerId,
                                    String fileName, String status, String verdict, UUID uploadedBy) {
}
