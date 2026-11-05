package ru.easyitsm.knowledge.events;

import java.util.UUID;

/** Статья опубликована ({@code article.published}). Слушатели: поиск, уведомления, аудит. */
public record ArticlePublished(UUID tenantId, UUID articleId, String key, int versionNo, String title,
                               String audience, UUID publishedBy) {
}
