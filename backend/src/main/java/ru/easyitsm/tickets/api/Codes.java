package ru.easyitsm.tickets.api;

import java.util.Locale;

/** Общие методы перечислений с кодом в нижнем регистре, который используется в REST и в настройках. */
final class Codes {

    private Codes() {
    }

    static String code(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    static <E extends Enum<E>> E parse(Class<E> type, String code, String what) {
        if (code == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Неизвестное значение " + what + ": " + code);
        }
    }
}
