package ru.easyitsm.attachments.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** DTO REST вложений. */
final class AttachmentViews {

    private AttachmentViews() {
    }

    /** Запрос ссылки на загрузку. */
    record NewUpload(@NotBlank String ownerType, @NotNull UUID ownerId, @NotBlank @Size(max = 255) String fileName,
                     @Positive long size, @Size(max = 255) String contentType, String visibility) {
    }

    /**
     * Файл.
     *
     * @param status      awaiting_upload, scanning, clean, infected, blocked
     * @param previewable можно показать картинкой ({@code /preview})
     * @param verdict     причина карантина: сигнатура или правило; только для статусов infected и blocked
     */
    record Attachment(UUID id, String ownerType, UUID ownerId, String visibility, String fileName, String contentType,
                      long size, String status, boolean previewable, String verdict, String sha256,
                      Instant scannedAt, UUID uploadedBy, Instant createdAt, boolean canDelete, long version) {
    }

    /** Ссылка на загрузку: браузер выполняет {@code method} по {@code url} с заголовками {@code headers}. */
    record Upload(String url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record Created(Attachment attachment, Upload upload) {
    }
}
