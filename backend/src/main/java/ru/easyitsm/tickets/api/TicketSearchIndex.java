package ru.easyitsm.tickets.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Заявки для глобального поиска (TCK-15, NFR-04). Модуль поиска строит индекс OpenSearch по
 * {@link #document}, фильтрует выдачу по {@link #readScope()} и перепроверяет её {@link #visibleAmong}: индекс
 * может отставать на секунды, а права — нет. Резервный поиск без OpenSearch — {@link #search} по PostgreSQL.
 * <p>
 * Правило чтения то же, что у REST заявок ({@code TicketAccess}): участник; конфиденциальная — ещё группа
 * безопасности; остальные — по области {@link TicketReadScope}. Меняя правило, меняйте и фильтр поиска:
 * это проверяет тест эквивалентности в модуле search.
 * <p>
 * Найденный фрагмент текста ({@code snippet}) размечает совпадения символами {@link #MARK_START} и
 * {@link #MARK_END}, сам текст — без разметки.
 */
public interface TicketSearchIndex {

    char MARK_START = '';
    char MARK_END = '';

    /**
     * Документ для индекса в текущем арендаторе, без проверки прав (вызывает индексатор). Пусто — заявки нет,
     * её нужно убрать из индекса.
     */
    Optional<TicketSearchDocument> document(UUID ticketId);

    /** Id заявок арендатора по возрастанию после {@code afterId} (null — с начала): полная переиндексация. */
    List<UUID> idsAfter(UUID afterId, int limit);

    /** Что может читать текущий пользователь (вход в {@code SecurityContext}). */
    TicketReadScope readScope();

    /** Какие из заявок текущий пользователь может читать сейчас. */
    Set<UUID> visibleAmong(Collection<UUID> ticketIds);

    /** Резервный поиск по PostgreSQL с русской морфологией и правами текущего пользователя. */
    List<TicketSearchHit> search(TicketTextQuery query);
}
