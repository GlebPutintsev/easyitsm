package ru.easyitsm.knowledge.events;

import java.util.UUID;

/** Проверяющий вернул статью на доработку с комментарием. Уведомление получает владелец. */
public record ArticleReturnedToDraft(UUID tenantId, UUID articleId, String key, String title, UUID ownerId,
                                     String comment, UUID returnedBy) {
}
