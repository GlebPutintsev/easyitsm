package ru.easyitsm.sla.internal;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.platform.web.ApiException;

/** Рабочий график (SLA-03): часовой пояс и часы по дням недели. Праздники — в {@link Holiday}. */
@Table("work_schedule")
class WorkSchedule extends TenantEntity {

    private String name;
    private String timezone;
    /** Семь строк часов с понедельника по воскресенье, формат {@link DayHours}. */
    private List<String> week;

    protected WorkSchedule() {
    }

    WorkSchedule(String name, String timezone, Map<DayOfWeek, List<String>> week) {
        this.name = name;
        setTimezone(timezone);
        setWeek(week);
    }

    void setName(String name) {
        this.name = name;
    }

    void setTimezone(String timezone) {
        try {
            this.timezone = ZoneId.of(timezone).getId();
        } catch (DateTimeException e) {
            throw ApiException.badRequest("invalid_timezone", "Неизвестный часовой пояс: " + timezone);
        }
    }

    void setWeek(Map<DayOfWeek, List<String>> days) {
        List<String> w = new ArrayList<>(7);
        for (DayOfWeek d : DayOfWeek.values()) {
            w.add(DayHours.normalize(days.get(d)));
        }
        if (w.stream().allMatch(String::isEmpty)) {
            throw ApiException.badRequest("empty_schedule", "В графике должен быть хотя бы один рабочий интервал");
        }
        this.week = w;
    }

    String name() {
        return name;
    }

    String timezone() {
        return timezone;
    }

    List<String> week() {
        return week;
    }

    Map<DayOfWeek, List<String>> weekView() {
        Map<DayOfWeek, List<String>> out = new LinkedHashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            out.put(d, DayHours.split(week.get(d.getValue() - 1)));
        }
        return out;
    }

    SlaViews.Schedule toView() {
        return new SlaViews.Schedule(getId(), name, timezone, weekView(), getUpdatedAt(), getVersion());
    }
}
