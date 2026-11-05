package ru.easyitsm.sla.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.sla.api.SlaSetup;

/** Настройка SLA администратором: графики, праздники, политики, проверка на примере, значения по умолчанию. */
@Service
class SlaAdminService implements SlaSetup {

    private final WorkScheduleRepository schedules;
    private final HolidayRepository holidays;
    private final SlaPolicyRepository policies;
    private final SlaInstanceRepository instances;
    private final Calendars calendars;

    SlaAdminService(WorkScheduleRepository schedules, HolidayRepository holidays, SlaPolicyRepository policies,
                    SlaInstanceRepository instances, Calendars calendars) {
        this.schedules = schedules;
        this.holidays = holidays;
        this.policies = policies;
        this.instances = instances;
        this.calendars = calendars;
    }

    // ---- графики ----

    @Transactional(readOnly = true)
    public CursorPage<SlaViews.Schedule> listSchedules(PageParams page) {
        KeysetCursor after = page.keyset();
        List<WorkSchedule> rows = schedules.page(after == null ? null : after.id(), page.limit() + 1);
        return CursorPage.of(rows, page.limit(), s -> new KeysetCursor("", s.getId())).map(WorkSchedule::toView);
    }

    @Transactional(readOnly = true)
    public SlaViews.Schedule getSchedule(UUID id) {
        return schedule(id).toView();
    }

    @Transactional
    public SlaViews.Schedule createSchedule(SlaCommands.ScheduleCreate cmd) {
        return schedules.save(new WorkSchedule(cmd.name(), cmd.timezone(), cmd.week())).toView();
    }

    @Transactional
    public SlaViews.Schedule updateSchedule(UUID id, String ifMatch, SlaCommands.ScheduleUpdate cmd) {
        WorkSchedule s = schedule(id);
        ETags.requireMatch(ifMatch, s.getVersion());
        if (cmd.name() != null) {
            s.setName(cmd.name());
        }
        if (cmd.timezone() != null) {
            s.setTimezone(cmd.timezone());
        }
        if (cmd.week() != null) {
            s.setWeek(cmd.week());
        }
        s = schedules.save(s);
        instances.requestRecalculation(id, Instant.now());
        return s.toView();
    }

    @Transactional(readOnly = true)
    public List<SlaViews.HolidayItem> holidays(UUID scheduleId, int year) {
        schedule(scheduleId);
        return holidays.findInRange(scheduleId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).stream()
                .map(Holiday::toView).toList();
    }

    /** Заменяет исключения графика за год, например производственный календарь (SLA-03). */
    @Transactional
    public List<SlaViews.HolidayItem> replaceHolidays(UUID scheduleId, int year, List<SlaCommands.HolidayInput> items) {
        schedule(scheduleId);
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        if (items.stream().map(SlaCommands.HolidayInput::date).distinct().count() != items.size()) {
            throw ApiException.badRequest("duplicate_date", "Дата встречается дважды");
        }
        List<Holiday> rows = new ArrayList<>();
        for (SlaCommands.HolidayInput h : items) {
            if (h.date().isBefore(from) || h.date().isAfter(to)) {
                throw ApiException.badRequest("date_out_of_year", "Дата " + h.date() + " не входит в " + year + " год");
            }
            rows.add(new Holiday(scheduleId, h.date(), h.name(), DayHours.normalize(h.hours())));
        }
        holidays.deleteInRange(scheduleId, from, to);
        holidays.saveAll(rows);
        instances.requestRecalculation(scheduleId, Instant.now());
        return holidays(scheduleId, year);
    }

    // ---- политики ----

    @Transactional(readOnly = true)
    public CursorPage<SlaViews.Policy> listPolicies(PageParams page) {
        KeysetCursor after = page.keyset();
        List<SlaPolicy> rows = policies.page(after == null ? null : after.id(), page.limit() + 1);
        return CursorPage.of(rows, page.limit(), p -> new KeysetCursor("", p.getId())).map(SlaPolicy::toView);
    }

    @Transactional(readOnly = true)
    public SlaViews.Policy getPolicy(UUID id) {
        return policy(id).toView();
    }

    @Transactional
    public SlaViews.Policy createPolicy(SlaCommands.PolicyCreate cmd) {
        var p = new SlaPolicy(cmd.name(), cmd.kind());
        p.setDescription(cmd.description());
        if (cmd.active() != null) {
            p.setActive(cmd.active());
        }
        if (cmd.rank() != null) {
            p.setRank(cmd.rank());
        }
        p.setScheduleId(checkedSchedule(cmd.scheduleId()));
        applyConditions(p, cmd.conditions());
        if (cmd.pauseStatuses() != null) {
            p.setPauseStatuses(cmd.pauseStatuses());
        }
        p.setTargets(targets(cmd.targets()));
        return policies.save(p).toView();
    }

    /** Изменения политики действуют на новые счётчики. Уже идущие сохраняют цель, с которой стартовали. */
    @Transactional
    public SlaViews.Policy updatePolicy(UUID id, String ifMatch, SlaCommands.PolicyUpdate cmd) {
        SlaPolicy p = policy(id);
        ETags.requireMatch(ifMatch, p.getVersion());
        if (cmd.name() != null) {
            p.setName(cmd.name());
        }
        if (cmd.description() != null) {
            p.setDescription(cmd.description());
        }
        if (cmd.active() != null) {
            p.setActive(cmd.active());
        }
        if (cmd.rank() != null) {
            p.setRank(cmd.rank());
        }
        if (Boolean.TRUE.equals(cmd.clearSchedule())) {
            p.setScheduleId(null);
        } else if (cmd.scheduleId() != null) {
            p.setScheduleId(checkedSchedule(cmd.scheduleId()));
        }
        applyConditions(p, cmd.conditions());
        if (cmd.pauseStatuses() != null) {
            p.setPauseStatuses(cmd.pauseStatuses());
        }
        if (cmd.targets() != null) {
            p.setTargets(targets(cmd.targets()));
        }
        return policies.save(p).toView();
    }

    /** «Проверить на примере» (раздел 10.16): сроки и пороги для заявки, созданной в заданный момент. */
    @Transactional(readOnly = true)
    public SlaViews.Preview preview(UUID policyId, SlaCommands.PreviewInput in) {
        SlaPolicy p = policy(policyId);
        List<Long[]> gaps = new ArrayList<>();
        if (in.pauses() != null) {
            for (SlaCommands.Interval i : in.pauses()) {
                gaps.add(new Long[] {i.from().toEpochMilli(), i.to().toEpochMilli()});
            }
        }
        Gaps g = Gaps.of(gaps);
        Calendars.Session cals = calendars.session();
        List<SlaViews.PreviewTarget> out = new ArrayList<>();
        for (SlaTarget t : p.targetsFor(in.priority())) {
            BusinessCalendar cal = cals.of(t.aroundTheClock() ? null : p.scheduleId());
            long ms = t.targetMs(cal);
            Map<Integer, Instant> th = new LinkedHashMap<>();
            for (int pct : t.thresholds()) {
                th.put(pct, cal.add(in.startAt(), Math.floorDiv(ms * pct, 100), g));
            }
            out.add(new SlaViews.PreviewTarget(t.metric(), t.priority(), ms / 1000, cal.add(in.startAt(), ms, g), th));
        }
        return new SlaViews.Preview(p.getId(), p.name(), p.kind(), out);
    }

    // ---- значения по умолчанию (раздел 5.3 ТЗ) ----

    @Override
    @Transactional
    public UUID installDefaults(ZoneId zone) {
        Map<DayOfWeek, List<String>> week = new LinkedHashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            week.put(d, d.getValue() <= 5 ? List.of("09:00-18:00") : List.of());
        }
        WorkSchedule s = schedules.save(new WorkSchedule("Рабочий график 09:00–18:00 пн–пт", zone.getId(), week));
        var p = new SlaPolicy("SLA по умолчанию", "sla");
        p.setDescription("Сроки реакции и решения из раздела 5.3: P1 и P2 круглосуточно, P3 и P4 по рабочему графику");
        p.setRank(1000);
        p.setScheduleId(s.getId());
        List<Integer> th = List.of(50, 75, 90);
        List<String> warn = List.of("notify:assignee");
        List<String> breach = List.of("notify:assignee", "notify:group_lead");
        p.setTargets(List.of(
                new SlaTarget("response", "P1", 15, "minutes", "24x7", th, warn, breach),
                new SlaTarget("resolution", "P1", 4, "hours", "24x7", th, warn, breach),
                new SlaTarget("response", "P2", 30, "minutes", "24x7", th, warn, breach),
                new SlaTarget("resolution", "P2", 8, "hours", "24x7", th, warn, breach),
                new SlaTarget("response", "P3", 4, "hours", "policy", th, warn, breach),
                new SlaTarget("resolution", "P3", 2, "business_days", "policy", th, warn, breach),
                new SlaTarget("response", "P4", 8, "hours", "policy", th, warn, breach),
                new SlaTarget("resolution", "P4", 5, "business_days", "policy", th, warn, breach)));
        return policies.save(p).getId();
    }

    private static List<SlaTarget> targets(List<SlaCommands.TargetInput> in) {
        return in.stream().map(t -> new SlaTarget(t.metric(), t.priority(), t.amount(), t.unit(),
                t.calendar() == null ? "policy" : t.calendar(),
                t.thresholds() == null ? List.of(50, 75, 90) : t.thresholds(),
                t.warnActions() == null ? List.of() : t.warnActions(),
                t.breachActions() == null ? List.of() : t.breachActions())).toList();
    }

    private static void applyConditions(SlaPolicy p, SlaCommands.ConditionsInput c) {
        if (c != null) {
            p.setConditions(c.ticketTypes(), c.priorities(), c.serviceIds(), c.clientIds(), c.groupIds());
        }
    }

    private UUID checkedSchedule(UUID id) {
        if (id != null) {
            schedule(id);
        }
        return id;
    }

    private WorkSchedule schedule(UUID id) {
        return schedules.findById(id).orElseThrow(() -> ApiException.notFound("График"));
    }

    private SlaPolicy policy(UUID id) {
        return policies.findById(id).orElseThrow(() -> ApiException.notFound("Политика SLA"));
    }
}
