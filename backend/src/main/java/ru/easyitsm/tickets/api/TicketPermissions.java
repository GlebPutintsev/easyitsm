package ru.easyitsm.tickets.api;

/**
 * Что текущий пользователь может делать с видимой заявкой: для модулей, которые прикрепляют к заявке свои
 * данные (вложения).
 *
 * @param canComment   пишет в ленту: все, кто видит заявку, кроме чистого аудитора
 * @param canSeeInternal видит внутренние заметки и внутренние файлы: поддержка и аудитор
 * @param canAddInternal добавляет внутренние заметки и файлы: поддержка
 * @param closed       заявка закрыта или отменена: публичное добавлять нельзя
 * @param canWork      работает с заявкой (поля, назначение, переходы): исполнитель, член группы, MIM;
 *                     нераспределённую — любой из поддержки, кто её видит
 */
public record TicketPermissions(TicketSnapshot ticket, boolean canComment, boolean canSeeInternal,
                                boolean canAddInternal, boolean closed, boolean canWork) {
}
