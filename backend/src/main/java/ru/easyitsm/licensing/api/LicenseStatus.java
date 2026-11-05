package ru.easyitsm.licensing.api;

import java.time.Instant;
import java.util.List;

/**
 * Текущее состояние лицензии.
 *
 * @param state        состояние
 * @param licenseId    номер лицензии, {@code null} в пробном режиме
 * @param licensee     владелец лицензии
 * @param notBefore    начало действия
 * @param notAfter     окончание действия
 * @param maxAgents    действующий лимит агентов: из лицензии, если она действует, иначе пробный
 * @param daysLeft     дней до окончания, {@code null} без действующей лицензии
 * @param features     дополнительные права из лицензии
 * @param problem      код причины для INVALID и EXPIRED, иначе {@code null}
 */
public record LicenseStatus(LicenseState state, String licenseId, String licensee, Instant notBefore,
                            Instant notAfter, int maxAgents, Long daysLeft, List<String> features,
                            String problem) {

    /** Лимит агентов в пробном режиме и без действующей лицензии. */
    public static final int TRIAL_AGENTS = 3;

    /** За сколько дней до окончания предупреждать администратора. */
    public static final int WARN_DAYS = 30;

    public boolean expiresSoon() {
        return state == LicenseState.VALID && daysLeft != null && daysLeft <= WARN_DAYS;
    }
}
