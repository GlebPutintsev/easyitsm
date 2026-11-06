package ru.easyitsm.tickets.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Чтение заявок другими модулями в пределах арендатора (RLS). {@link #find} и {@link #findByNumber} —
 * без проверки прав пользователя, {@link #findVisible} — с правилами доступа модуля заявок.
 */
public interface TicketQueries {

    Optional<TicketSnapshot> find(UUID ticketId);

    /** По номеру вида {@code INC-000123}. */
    Optional<TicketSnapshot> findByNumber(String number);

    /**
     * Заявка, которую текущий пользователь (вход в {@code SecurityContext}) может читать по правилам модуля
     * заявок (ACC-01, ACC-04): участник, конфиденциальная — только группа безопасности. Чужая, невидимая или
     * отсутствующая — пусто, модуль отвечает 404, как REST заявок.
     */
    Optional<TicketSnapshot> findVisible(UUID ticketId);

    /** Видимая текущему пользователю заявка и его права на ленту; невидимая — пусто (404). */
    Optional<TicketPermissions> permissions(UUID ticketId);
    /**
     * Запись ленты по id из {@code TicketCommentAdded.activityId} — текст ответа для письма заявителю.
     * Без проверки прав: вызывающий сам решает, кому можно показать запись (внутреннюю — только поддержке,
     * конфиденциальную — никому в письме, NTF-05).
     */
    Optional<TicketComment> findComment(UUID ticketId, UUID activityId);

    /** Наблюдатели заявки (TCK-10), в порядке подписки. */
    List<UUID> watchers(UUID ticketId);
}
