package ru.easyitsm.integrations.internal;

import java.util.Locale;

/**
 * Важность алерта, приведённая к четырём уровням. Каждый источник называет её по-своему: у Zabbix шесть
 * уровней, у Alertmanager и Grafana — свободная метка {@code severity}.
 */
enum Severity {
    CRITICAL, HIGH, WARNING, INFO;

    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Чем меньше, тем важнее. */
    boolean atLeast(Severity other) {
        return ordinal() <= other.ordinal();
    }

    static Severity fromCode(String code) {
        for (Severity s : values()) {
            if (s.code().equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Неизвестная важность " + code);
    }

    static boolean isCode(String code) {
        for (Severity s : values()) {
            if (s.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Свободное название важности из любого источника. Неизвестное — {@link #WARNING}: событие не потеряется,
     * но и не поднимет P1.
     */
    static Severity normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return WARNING;
        }
        String s = raw.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        return switch (s) {
            case "disaster", "critical", "crit", "fatal", "emergency", "emerg", "alert", "page", "p1", "5", "sev1" ->
                    CRITICAL;
            case "high", "error", "err", "major", "p2", "4", "sev2" -> HIGH;
            case "average", "warning", "warn", "minor", "medium", "p3", "3", "2", "sev3" -> WARNING;
            case "information", "info", "informational", "not_classified", "notice", "low", "debug", "none", "ok",
                 "p4", "p5", "1", "0", "sev4" -> INFO;
            default -> WARNING;
        };
    }
}
