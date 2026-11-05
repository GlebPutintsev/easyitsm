package ru.easyitsm.tickets.api;

/** Приоритет P1 (наивысший) – P4 (раздел 5.2 ТЗ). */
public enum Priority {
    P1, P2, P3, P4;

    public String code() {
        return name();
    }

    public static Priority fromCode(String code) {
        return Codes.parse(Priority.class, code, "приоритета");
    }
}
