package ru.easyitsm.licensing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.easyitsm.licensing.api.LicenseState;
import ru.easyitsm.licensing.api.LicenseStatus;
import ru.easyitsm.licensing.internal.LicenseService.LoadResult;

/** Расчёт состояния лицензии по сроку действия. */
class LicenseStatusTest {

    static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    static final VerifiedLicense LICENSE = new VerifiedLicense("EI-1", "ООО Тест", FROM, FROM, TO, 50, List.of(), "k");

    @Test
    void noLicenseIsTrial() {
        LicenseStatus s = LicenseService.statusOf(LoadResult.NONE, FROM);
        assertThat(s.state()).isEqualTo(LicenseState.TRIAL);
        assertThat(s.maxAgents()).isEqualTo(LicenseStatus.TRIAL_AGENTS);
    }

    @Test
    void brokenLicenseIsInvalidWithTrialLimit() {
        LicenseStatus s = LicenseService.statusOf(new LoadResult(null, "license_bad_signature"), FROM);
        assertThat(s.state()).isEqualTo(LicenseState.INVALID);
        assertThat(s.problem()).isEqualTo("license_bad_signature");
        assertThat(s.maxAgents()).isEqualTo(LicenseStatus.TRIAL_AGENTS);
    }

    @Test
    void validLicenseGivesItsLimit() {
        LicenseStatus s = LicenseService.statusOf(new LoadResult(LICENSE, null), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(s.state()).isEqualTo(LicenseState.VALID);
        assertThat(s.maxAgents()).isEqualTo(50);
        assertThat(s.expiresSoon()).isFalse();
    }

    @Test
    void warnsThirtyDaysBeforeEnd() {
        LicenseStatus s = LicenseService.statusOf(new LoadResult(LICENSE, null), Instant.parse("2026-12-10T00:00:00Z"));
        assertThat(s.daysLeft()).isEqualTo(22);
        assertThat(s.expiresSoon()).isTrue();
    }

    @Test
    void boundariesOfValidity() {
        assertThat(LicenseService.statusOf(new LoadResult(LICENSE, null), FROM.minusSeconds(1)).state())
                .isEqualTo(LicenseState.NOT_YET_VALID);
        assertThat(LicenseService.statusOf(new LoadResult(LICENSE, null), FROM).state()).isEqualTo(LicenseState.VALID);
        LicenseStatus expired = LicenseService.statusOf(new LoadResult(LICENSE, null), TO);
        assertThat(expired.state()).isEqualTo(LicenseState.EXPIRED);
        assertThat(expired.maxAgents()).isEqualTo(LicenseStatus.TRIAL_AGENTS);
    }
}
