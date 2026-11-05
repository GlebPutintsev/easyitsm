package ru.easyitsm.knowledge.events;

import java.util.UUID;

/** Статья перенесена в архив и убрана из поиска. Связи с заявками сохраняются. */
public record ArticleArchived(UUID tenantId, UUID articleId, String key, UUID archivedBy) {
}
