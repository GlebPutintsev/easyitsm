package ru.easyitsm.tickets.api;

import java.util.UUID;

/**
 * Изменение заявки другим модулем (автоматизация) через {@link TicketCommands#change}. null — не менять.
 *
 * @param priority      ручной приоритет поверх матрицы; причина — {@code reason}
 * @param groupId       новая группа; исполнитель прежней группы снимается, новый выбирается распределением группы,
 *                      если {@code assigneeId} не задан
 * @param clearAssignee снять исполнителя
 * @param reason        причина изменения для истории, например «Правило автоматизации «…»»
 */
public record TicketChange(
        Priority priority,
        Level impact,
        Level urgency,
        UUID groupId,
        UUID assigneeId,
        boolean clearAssignee,
        String reason) {
}
