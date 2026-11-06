package ru.easyitsm.attachments.internal;

import java.time.Instant;
import java.util.UUID;

/** Строка таблицы attachment. */
record AttachmentRow(
        UUID id,
        UUID tenantId,
        String ownerType,
        UUID ownerId,
        String visibility,
        String fileName,
        String declaredType,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageKey,
        String status,
        String scanEngine,
        String scanVerdict,
        Instant scannedAt,
        int scanAttempts,
        Instant uploadExpiresAt,
        Instant deletedAt,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt,
        long version) {

    static final String AWAITING = "awaiting_upload";
    static final String SCANNING = "scanning";
    static final String CLEAN = "clean";
    static final String INFECTED = "infected";
    static final String BLOCKED = "blocked";

    boolean internal() {
        return "internal".equals(visibility);
    }

    boolean quarantined() {
        return INFECTED.equals(status) || BLOCKED.equals(status);
    }

    /** Ключ в корзине файлов после проверки. */
    String cleanKey() {
        return "files/" + tenantId + "/" + id;
    }
}
