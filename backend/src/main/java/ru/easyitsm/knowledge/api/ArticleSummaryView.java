package ru.easyitsm.knowledge.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Краткое представление статьи для списков, поиска и подсказок.
 *
 * @param key     номер для людей, например {@code KB-42}
 * @param snippet фрагмент текста без разметки
 */
public record ArticleSummaryView(
        UUID id,
        String key,
        String title,
        String snippet,
        String status,
        String audience,
        UUID categoryId,
        Instant publishedAt,
        Instant updatedAt) {
}
