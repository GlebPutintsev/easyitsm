package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Повторяющиеся инциденты (PRB-05): группы инцидентов с одной услугой, категорией или КЕ за период — источник
 * новых проблем. Конфиденциальные заявки не входят. Права пользователя проверяет вызывающий модуль.
 */
public interface TicketTrends {

    /** Чем группируются инциденты. */
    enum Dimension {
        SERVICE, CATEGORY, CI
    }

    /**
     * @param minCount минимум инцидентов в группе
     * @param limit    сколько групп вернуть, по убыванию числа инцидентов
     */
    List<IncidentCluster> recurringIncidents(Dimension dimension, Instant from, Instant to, int minCount, int limit);

    /**
     * @param key        услуга, категория или КЕ
     * @param ticketIds  инциденты группы, новые первыми, не больше 100
     * @param open       сколько из них ещё не решено
     */
    record IncidentCluster(UUID key, long count, long open, Instant firstAt, Instant lastAt, List<UUID> ticketIds) {
    }
}
