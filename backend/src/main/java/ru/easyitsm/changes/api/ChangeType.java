package ru.easyitsm.changes.api;

import java.util.Locale;
import ru.easyitsm.platform.web.ApiException;

/** Тип изменения (CHG-01). */
public enum ChangeType {
    /** Из шаблона с предварительным утверждением: без оценки и согласования. */
    STANDARD,
    /** Оценка риска, маршрут согласования, при необходимости CAB. */
    NORMAL,
    /** Сокращённый маршрут, согласование после работ (CHG-12). */
    EMERGENCY;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ChangeType fromCode(String code) {
        for (ChangeType t : values()) {
            if (t.code().equals(code)) {
                return t;
            }
        }
        throw ApiException.badRequest("invalid_change_type", "Тип изменения: standard, normal или emergency");
    }
}
