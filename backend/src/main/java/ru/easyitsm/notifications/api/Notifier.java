package ru.easyitsm.notifications.api;

import java.util.UUID;

/**
 * Уведомление пользователю от другого модуля: запись в центре уведомлений, push и, если {@code email}, письмо.
 * Выполняется в транзакции и арендаторе вызывающего. Текст вызывающий готовит сам и не кладёт в него
 * конфиденциальные данные (NTF-05).
 */
public interface Notifier {

    /**
     * @param kind  вид для журнала и фильтров, например {@code automation}
     * @param link  относительная ссылка в интерфейсе, например {@code /agent/tickets/INC-000123}; null — без ссылки
     * @param email отправить ещё и письмо на почту пользователя
     */
    void notify(UUID userId, String kind, String title, String body, String link, boolean email);
}
