package ru.easyitsm.sla.internal;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;

/**
 * Исключение графика на дату: праздник (пустые часы), сокращённый предпраздничный или перенесённый рабочий день
 * (особые часы). Так описывается производственный календарь.
 */
@Table("holiday")
class Holiday extends TenantEntity {

    private UUID scheduleId;
    private LocalDate day;
    private String name;
    private String hours;

    protected Holiday() {
    }

    Holiday(UUID scheduleId, LocalDate day, String name, String hours) {
        this.scheduleId = scheduleId;
        this.day = day;
        this.name = name == null ? "" : name;
        this.hours = hours == null ? "" : hours;
    }

    LocalDate day() {
        return day;
    }

    String hours() {
        return hours;
    }

    SlaViews.HolidayItem toView() {
        return new SlaViews.HolidayItem(day, name, DayHours.split(hours));
    }
}
