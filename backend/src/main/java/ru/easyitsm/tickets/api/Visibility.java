package ru.easyitsm.tickets.api;

/** Видимость записи ленты: PUBLIC видит заявитель, INTERNAL — только поддержка (TCK-03). */
public enum Visibility {
    PUBLIC, INTERNAL;

    public String code() {
        return Codes.code(this);
    }

    public static Visibility fromCode(String code) {
        return Codes.parse(Visibility.class, code, "видимости");
    }
}
