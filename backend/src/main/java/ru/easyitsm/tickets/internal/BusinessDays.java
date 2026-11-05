package ru.easyitsm.tickets.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Рабочие дни для автозакрытия: пропускаются суббота и воскресенье, время UTC.
 * TODO(sla): брать рабочий график и праздники арендатора из модуля sla, когда он отдаст их в api.
 */
final class BusinessDays {

    private BusinessDays() {
    }

    static Instant add(Instant from, int days) {
        ZonedDateTime t = from.atZone(ZoneOffset.UTC);
        int added = 0;
        while (added < days) {
            t = t.plusDays(1);
            if (t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return t.toInstant();
    }
}
