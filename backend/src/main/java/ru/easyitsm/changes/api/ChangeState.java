package ru.easyitsm.changes.api;

import java.util.Locale;

/** Статус изменения (5.6 ТЗ). Совпадает с кодом статуса заявки типа change. */
public enum ChangeState {
    DRAFT, ASSESSMENT, APPROVAL, SCHEDULED, IMPLEMENTATION, VERIFICATION, REVIEW, CLOSED, REJECTED, CANCELLED;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Закрыто, отклонено или отменено: изменение больше не меняется и не участвует в конфликтах. */
    public boolean isFinal() {
        return this == CLOSED || this == REJECTED || this == CANCELLED;
    }

    /** До начала внедрения: можно отменить, менять окно и план. */
    public boolean beforeImplementation() {
        return this == DRAFT || this == ASSESSMENT || this == APPROVAL || this == SCHEDULED;
    }

    public static ChangeState fromCode(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
