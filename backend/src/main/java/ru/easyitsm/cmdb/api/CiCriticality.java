package ru.easyitsm.cmdb.api;

/** Критичность КЕ для бизнеса: влияет на приоритет инцидентов и риск изменений. */
public enum CiCriticality {
    LOW, MEDIUM, HIGH, CRITICAL;

    public String code() {
        return name().toLowerCase();
    }

    public static CiCriticality fromCode(String code) {
        return valueOf(code.toUpperCase());
    }
}
