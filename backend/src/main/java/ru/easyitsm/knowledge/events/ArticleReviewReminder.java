package ru.easyitsm.knowledge.events;

import java.time.Instant;
import java.util.UUID;

/** Напоминание владельцу: срок пересмотра статьи скоро наступит (KB-03). */
public record ArticleReviewReminder(UUID tenantId, UUID articleId, String key, String title, UUID ownerId,
                                    Instant reviewAt) {
}
