package ru.easyitsm.mail.api;

import java.util.UUID;

/**
 * Очередь исходящей почты. Запись идёт в транзакции и арендаторе вызывающего, отправку с повторами выполняет
 * роль worker. Если отправка не удалась после всех попыток, публикуется
 * {@link ru.easyitsm.mail.events.MailDeliveryFailed}.
 */
public interface MailOutbox {

    /**
     * Ставит письмо в очередь. Письма на адреса собственных ящиков приёма не отправляются (защита от петли),
     * такая запись сразу получает статус {@code suppressed}.
     *
     * @return id записи очереди
     */
    UUID enqueue(OutgoingMail mail);
}
