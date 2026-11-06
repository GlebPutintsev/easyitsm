package ru.easyitsm.knowledge.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Публичный интерфейс базы знаний для других модулей. Все методы учитывают права текущего пользователя:
 * возвращаются только опубликованные статьи, видимые ему по аудитории.
 */
public interface ArticleQueries {

    /**
     * Подсказки статей по тексту обращения (INC-05, PRT-02): лучшие совпадения по любому из слов.
     */
    List<ArticleSummaryView> suggest(String text, int limit);

    /** Опубликованная статья, если она видна текущему пользователю. */
    Optional<ArticleSummaryView> findReadable(UUID articleId);

    /** Права на статью, которую пользователь может хотя бы читать (в любом статусе); иначе пусто (404). */
    Optional<ArticlePermissions> permissions(UUID articleId);
}
