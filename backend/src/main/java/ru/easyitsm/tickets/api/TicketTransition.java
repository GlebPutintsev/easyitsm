package ru.easyitsm.tickets.api;

/**
 * Переход заявки, который выполняет другой модуль ({@link TicketCommands#transition}). Обязательные поля перехода
 * проверяются как у пользователя: для решения нужен {@code resolutionCode}, для ожидания — {@code reasonCode}.
 *
 * @param to      код статуса цикла типа заявки
 * @param comment публичный комментарий перехода
 */
public record TicketTransition(
        String to,
        String comment,
        String reasonCode,
        String resolutionCode,
        String resolutionText) {
}
