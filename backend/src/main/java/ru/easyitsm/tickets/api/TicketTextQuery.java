package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.Set;

/**
 * Запрос резервного поиска заявок.
 *
 * @param types       типы заявок, пусто — все
 * @param state       {@code active} — в работе, {@code closed} — закрытые и отменённые, null — все
 * @param updatedFrom изменены не раньше
 */
public record TicketTextQuery(String text, Set<TicketType> types, String state, Instant updatedFrom, int limit) {

    public TicketTextQuery {
        types = types == null ? Set.of() : Set.copyOf(types);
    }
}
