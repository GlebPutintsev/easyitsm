package ru.easyitsm.tickets.api;

/**
 * Уровень влияния или срочности. Влияние: HIGH — услуга или организация, MEDIUM — подразделение,
 * LOW — один пользователь.
 */
public enum Level {
    HIGH, MEDIUM, LOW;

    public String code() {
        return Codes.code(this);
    }

    public static Level fromCode(String code) {
        return Codes.parse(Level.class, code, "уровня");
    }
}
