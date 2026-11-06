package ru.easyitsm.knowledge.api;

import java.time.Instant;
import java.util.UUID;

/** Опубликованная версия статьи для поискового индекса. {@code body} — текст без разметки Markdown. */
public record ArticleSearchDocument(
        UUID id,
        String key,
        String title,
        String body,
        String status,
        ArticleAudience audience,
        UUID audienceGroupId,
        UUID categoryId,
        Instant publishedAt) {
}
