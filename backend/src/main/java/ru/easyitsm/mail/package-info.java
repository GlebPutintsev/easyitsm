/**
 * Канал почты (CHN-01, CHN-02, поток 6.4 ТЗ): отправка писем через SMTP или Microsoft Graph с повторами,
 * приём обращений из IMAP, Microsoft Graph и входящего SMTP, проверка SPF, DKIM и DMARC, отсечение автоответов
 * и почтовых петель, токен ответа, который связывает письмо с заявкой.
 * <p>
 * Контракт для других модулей:
 * <ul>
 *   <li>{@code api} — {@link ru.easyitsm.mail.api.MailOutbox}: поставить письмо в очередь отправки;</li>
 *   <li>{@code events} — {@link ru.easyitsm.mail.events.MailDeliveryFailed} (notification.failed) и
 *       {@link ru.easyitsm.mail.events.InboundMailProcessed};</li>
 *   <li>REST: {@code /api/v1/mail-settings/**} для администратора.</li>
 * </ul>
 * Заявки создаются и дополняются только через {@code ru.easyitsm.tickets.api.TicketCommands}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Mail")
package ru.easyitsm.mail;
