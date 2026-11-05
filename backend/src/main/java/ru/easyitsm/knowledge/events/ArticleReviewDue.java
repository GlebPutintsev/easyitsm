package ru.easyitsm.knowledge.events;

import java.time.Instant;
import java.util.UUID;

/** Наступил срок пересмотра: статья переведена в «На пересмотре». Уведомление получает владелец. */
public record ArticleReviewDue(UUID tenantId, UUID articleId, String key, String title, UUID ownerId,
                               Instant reviewAt) {
}
