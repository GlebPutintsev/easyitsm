package ru.easyitsm.changes.api;

import java.util.Locale;
import ru.easyitsm.platform.web.ApiException;

/** Результат внедрения (CHG-11). */
public enum ChangeOutcome {
    SUCCESSFUL, PARTIAL, ROLLED_BACK;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ChangeOutcome fromCode(String code) {
        for (ChangeOutcome o : values()) {
            if (o.code().equals(code)) {
                return o;
            }
        }
        throw ApiException.badRequest("invalid_outcome", "Результат: successful, partial или rolled_back");
    }
}
