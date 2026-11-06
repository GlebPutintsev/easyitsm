package ru.easyitsm.changes.api;

import java.util.Locale;
import ru.easyitsm.platform.web.ApiException;

/** Уровень риска изменения (CHG-04). */
public enum RiskLevel {
    LOW, MEDIUM, HIGH;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RiskLevel fromCode(String code) {
        for (RiskLevel r : values()) {
            if (r.code().equals(code)) {
                return r;
            }
        }
        throw ApiException.badRequest("invalid_risk_level", "Уровень риска: low, medium или high");
    }
}
