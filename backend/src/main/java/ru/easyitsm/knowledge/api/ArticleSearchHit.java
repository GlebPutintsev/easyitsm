package ru.easyitsm.knowledge.api;

import java.time.Instant;
import java.util.UUID;

/** Найденная статья. {@code snippet} размечен символами {@link ArticleSearchIndex#MARK_START}/{@code MARK_END}. */
public record ArticleSearchHit(UUID id, String key, String title, String snippet, UUID categoryId,
                               Instant publishedAt, double rank) {
}
