package ru.easyitsm.sla.internal;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Собирает {@link BusinessCalendar} из графика и его праздников. */
@Component
class Calendars {

    private final WorkScheduleRepository schedules;
    private final HolidayRepository holidays;

    Calendars(WorkScheduleRepository schedules, HolidayRepository holidays) {
        this.schedules = schedules;
        this.holidays = holidays;
    }

    /** Календари на одну операцию: каждый график читается из БД один раз. */
    Session session() {
        return new Session();
    }

    BusinessCalendar load(UUID scheduleId) {
        if (scheduleId == null) {
            return BusinessCalendar.ALWAYS;
        }
        WorkSchedule s = schedules.findById(scheduleId)
                .orElseThrow(() -> new IllegalStateException("График не найден: " + scheduleId));
        Map<java.time.LocalDate, String> ex = new LinkedHashMap<>();
        holidays.findByScheduleIdOrderByDay(scheduleId).forEach(h -> ex.put(h.day(), h.hours()));
        return new BusinessCalendar(ZoneId.of(s.timezone()), s.week(), ex);
    }

    final class Session {
        private final Map<UUID, BusinessCalendar> cache = new HashMap<>();

        BusinessCalendar of(UUID scheduleId) {
            if (scheduleId == null) {
                return BusinessCalendar.ALWAYS;
            }
            return cache.computeIfAbsent(scheduleId, Calendars.this::load);
        }
    }
}
