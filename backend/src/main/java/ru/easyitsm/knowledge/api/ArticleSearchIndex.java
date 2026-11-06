package ru.easyitsm.knowledge.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Опубликованные статьи для глобального поиска (TCK-15, PRT-02). Индексатор берёт {@link #document}, выдача
 * фильтруется по {@link #readScope()} и перепроверяется {@link #readableAmong}. Без OpenSearch —
 * {@link #search} по PostgreSQL. Права — правило аудитории KB-04, как в {@code ArticleAccess.canReadPublished}.
 * <p>
 * Фрагмент текста размечает совпадения символами {@link #MARK_START} и {@link #MARK_END}.
 */
public interface ArticleSearchIndex {

    char MARK_START = '';
    char MARK_END = '';

    /** Опубликованная версия для индекса, без проверки прав. Пусто — статьи нет или она не опубликована. */
    Optional<ArticleSearchDocument> document(UUID articleId);

    /** Id статей арендатора по возрастанию после {@code afterId} (null — с начала). */
    List<UUID> idsAfter(UUID afterId, int limit);

    /** Аудитории, в которые входит текущий пользователь. */
    ArticleReadScope readScope();

    /** Какие из статей текущий пользователь может читать сейчас. */
    Set<UUID> readableAmong(Collection<UUID> articleIds);

    /** Резервный поиск по опубликованным статьям с русской морфологией. */
    List<ArticleSearchHit> search(String text, int limit);
}
