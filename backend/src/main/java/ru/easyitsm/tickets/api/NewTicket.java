package ru.easyitsm.tickets.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Заявка, которую создаёт другой модуль: каталог, почта, мониторинг, автоматизация.
 * Права вызывающий модуль проверяет сам; модуль заявок проверяет только данные.
 *
 * @param impact            влияние, null — LOW
 * @param urgency           срочность, null — MEDIUM
 * @param groupId           прямое назначение (правило 1 раздела 5.8), null — по правилам маршрутизации
 * @param custom            значения настраиваемых полей по их кодам, проверяются по определениям полей
 * @param awaitingApproval  запрос стартует в статусе категории APPROVAL (цикл 5.4), до решения согласующих
 * @param actorId           от чьего имени действие, null — система
 */
public record NewTicket(
        TicketType type,
        String title,
        String description,
        UUID requesterId,
        UUID createdForId,
        UUID organizationId,
        Channel channel,
        Level impact,
        Level urgency,
        UUID serviceId,
        UUID ciId,
        UUID categoryId,
        UUID catalogItemId,
        UUID groupId,
        UUID parentId,
        Map<String, Object> custom,
        boolean awaitingApproval,
        UUID actorId) {

    public NewTicket {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(requesterId, "requesterId");
        Objects.requireNonNull(channel, "channel");
        description = description == null ? "" : description;
        custom = custom == null ? Map.of() : Map.copyOf(custom);
    }

    /** Минимальная заявка: остальные поля null, создаёт система. */
    public static NewTicket of(TicketType type, String title, String description, UUID requesterId, Channel channel) {
        return new NewTicket(type, title, description, requesterId, null, null, channel, null, null, null, null, null,
                null, null, null, Map.of(), false, null);
    }
}
