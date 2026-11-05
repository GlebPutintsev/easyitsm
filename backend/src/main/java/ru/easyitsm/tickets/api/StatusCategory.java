package ru.easyitsm.tickets.api;

/**
 * Фиксированная категория поверх настраиваемых статусов. Другие модули опираются только на неё:
 * SLA считает {@link #PENDING} паузой, {@link #IN_PROGRESS} или первый публичный ответ агента — реакцией,
 * {@link #RESOLVED} — решением, {@link #CLOSED} и {@link #CANCELLED} — остановкой.
 * {@link #DRAFT} и {@link #APPROVAL} — заявка ещё не в работе (черновик, ожидание согласования запроса).
 */
public enum StatusCategory {
    DRAFT, NEW, APPROVAL, ASSIGNED, IN_PROGRESS, PENDING, RESOLVED, CLOSED, CANCELLED;

    /** Заявка завершена: закрыта или отменена. Изменения запрещены, кроме ленты для участников. */
    public boolean isFinal() {
        return this == CLOSED || this == CANCELLED;
    }

    /** Заявка в работе у поддержки: от создания до решения. */
    public boolean isActive() {
        return this == NEW || this == APPROVAL || this == ASSIGNED || this == IN_PROGRESS || this == PENDING;
    }

    public String code() {
        return Codes.code(this);
    }

    public static StatusCategory fromCode(String code) {
        return Codes.parse(StatusCategory.class, code, "категории статуса");
    }
}
