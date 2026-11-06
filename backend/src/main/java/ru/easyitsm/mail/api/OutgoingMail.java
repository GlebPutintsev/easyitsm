package ru.easyitsm.mail.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Письмо в очередь отправки. Тема и текст уже готовы: шаблоны и маскирование конфиденциального (NTF-05)
 * делает модуль уведомлений.
 *
 * @param kind            вид письма для журнала, например {@code ticket_reply} или {@code password_reset}
 * @param recipientUserId получатель-пользователь, если известен (журнал доставки)
 * @param ticketId        заявка письма: тогда в письмо добавляются токен ответа и заголовки цепочки,
 *                        и ответ на письмо попадёт в ленту этой заявки
 * @param ticketNumber    номер заявки, добавляется в тему в виде {@code [INC-000123]}
 * @param html            HTML-версия, null — только текст
 */
public record OutgoingMail(
        String kind,
        String recipient,
        String recipientName,
        UUID recipientUserId,
        UUID ticketId,
        String ticketNumber,
        String subject,
        String text,
        String html) {

    public OutgoingMail {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(subject, "subject");
        text = text == null ? "" : text;
    }
}
