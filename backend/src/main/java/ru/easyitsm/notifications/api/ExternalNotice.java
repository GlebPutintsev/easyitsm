package ru.easyitsm.notifications.api;

import java.util.UUID;

/**
 * Уведомление для внешнего канала. Конфиденциальная заявка уже замаскирована (NTF-05): в {@code subject}
 * и {@code text} нет её названия и текста ответа. Какие поля уйдут наружу, решает канал по своей настройке
 * (CHN-06).
 *
 * @param kind         вид уведомления, например {@code ticket.comment}
 * @param subject      заголовок, как в письме
 * @param text         краткий текст, как в центре уведомлений
 * @param ticketTitle  название заявки (у конфиденциальной — нейтральное) или null
 * @param url          полная ссылка в интерфейс или null
 * @param confidential заявка конфиденциальная
 */
public record ExternalNotice(UUID userId, String locale, String kind, String subject, String text, UUID ticketId,
                             String ticketNumber, String ticketTitle, String url, boolean confidential) {
}
