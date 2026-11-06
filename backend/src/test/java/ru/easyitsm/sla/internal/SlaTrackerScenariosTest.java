package ru.easyitsm.sla.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.sla.api.SlaInstanceView;
import ru.easyitsm.sla.api.SlaQueries;
import ru.easyitsm.sla.events.SlaBreached;
import ru.easyitsm.sla.events.SlaWarning;

/**
 * Эталонные сценарии SLA-01…06 на настоящей БД: снимки заявки проходят через {@link SlaTracker}, таймеры —
 * через {@link SlaTimerJob}. Ожидаемые сроки посчитаны вручную, сравнение точное (NFR-05 допускает 1 минуту).
 * Каждый сценарий работает со своей услугой, поэтому политики сценариев не пересекаются.
 */
@SpringBootTest
@ActiveProfiles("test")
@RecordApplicationEvents
class SlaTrackerScenariosTest {

    static final UUID DEMO = UUID.fromString("01930000-0000-7000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("01930000-0000-7000-8000-000000000002");
    static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    static final Instant T10 = Instant.parse("2026-11-05T10:00:00Z");

    @Autowired
    SlaTracker tracker;
    @Autowired
    SlaTimerJob job;
    @Autowired
    SlaAdminService admin;
    @Autowired
    SlaQueries queries;
    @Autowired
    SlaQueryService queryService;
    @Autowired
    ApplicationEvents events;
    @Autowired
    org.springframework.context.ApplicationEventPublisher publisher;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    // ---- слушатель событий заявок ----

    @Test
    void ticketEventsDriveCountersThroughOutbox() {
        UUID service = UUID.randomUUID();
        policy(DEMO, "Из событий", "sla", 10, service, null, 30, 480);
        UUID ticket = UUID.randomUUID();
        Instant created = Instant.now().minusSeconds(60);
        var snapshot = new ru.easyitsm.tickets.api.TicketSnapshot(ticket, "INC-000001",
                ru.easyitsm.tickets.api.TicketType.INCIDENT, "Не работает почта", "new",
                ru.easyitsm.tickets.api.StatusCategory.NEW, ru.easyitsm.tickets.api.Priority.P2, null, null,
                null, null, null, null, null, service, null, null, null, null, false, null, null, created, null, null,
                null, 0);
        TenantContext.runAs(DEMO, () -> tx.executeWithoutResult(s -> publisher.publishEvent(
                new ru.easyitsm.tickets.events.TicketCreated(DEMO, created, null, snapshot))));
        assertThat(instances(DEMO, ticket)).extracting(SlaInstanceView::metric)
                .containsExactlyInAnyOrder("response", "resolution");
        assertDue(DEMO, ticket, "resolution", created.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .plusSeconds(480 * 60));
    }

    // ---- SLA-01, раздел 5.3: сроки по умолчанию ----

    @Test
    void sla01_defaultTargetsFromSection53() {
        // Политика по умолчанию без условий подходит ко всем заявкам арендатора; чтобы сценарий не зависел
        // от политик других тестов, ограничиваем её своей услугой и ставим первой.
        UUID service = UUID.randomUUID();
        TenantContext.runAs(OTHER, () -> admin.updatePolicy(admin.installDefaults(MSK), "\"0\"",
                new SlaCommands.PolicyUpdate(null, null, null, 0, null, null,
                        new SlaCommands.ConditionsInput(null, null, List.of(service), null, null), null, null)));
        Instant created = msk("2026-11-05T10:00");

        Facts p3 = new Facts(UUID.randomUUID(), service).at(created).priority("P3");
        apply(OTHER, p3, created);
        // Реакция 4 ч по графику: чт 10:00 → 14:00. Решение 2 рабочих дня = 18 ч: чт 8 ч, пт 9 ч, пн 1 ч.
        assertDue(OTHER, p3.ticket, "response", msk("2026-11-05T14:00"));
        assertDue(OTHER, p3.ticket, "resolution", msk("2026-11-09T10:00"));

        Facts p1 = new Facts(UUID.randomUUID(), service).at(created).priority("P1");
        apply(OTHER, p1, created);
        assertDue(OTHER, p1.ticket, "response", msk("2026-11-05T10:15"));
        assertDue(OTHER, p1.ticket, "resolution", msk("2026-11-05T14:00"));

        Facts p4 = new Facts(UUID.randomUUID(), service).at(msk("2026-11-06T17:00")).priority("P4");
        apply(OTHER, p4, msk("2026-11-06T17:00"));
        // Реакция 8 ч: пт 1 ч, пн 7 ч → пн 16:00. Решение 5 рабочих дней = 45 ч: пт 1, пн–пт 44 → пт 13.11 17:00.
        assertDue(OTHER, p4.ticket, "response", msk("2026-11-09T16:00"));
        assertDue(OTHER, p4.ticket, "resolution", msk("2026-11-13T17:00"));
    }

    // ---- SLA-02: условия применения ----

    @Test
    void sla02_policyChosenByTypeClientServiceAndRank() {
        UUID service = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID vip = policy(DEMO, "VIP", "sla", 10, service, c -> new SlaCommands.ConditionsInput(
                List.of("incident"), null, List.of(service), List.of(client), null), 60, 60);
        UUID general = policy(DEMO, "Общая", "sla", 20, service, null, 240, 480);

        Facts incident = new Facts(UUID.randomUUID(), service).at(T10).client(client);
        apply(DEMO, incident, T10);
        assertThat(instances(DEMO, incident.ticket)).allMatch(i -> i.policyId().equals(vip));

        Facts request = new Facts(UUID.randomUUID(), service).at(T10).client(client).type("request");
        apply(DEMO, request, T10);
        assertThat(instances(DEMO, request.ticket)).allMatch(i -> i.policyId().equals(general));

        // Клиент сменился: VIP больше не подходит. Новая политика наследует старт, затраченное время не теряется.
        apply(DEMO, incident.at(T10.plusSeconds(1800)).client(UUID.randomUUID()), T10.plusSeconds(1800));
        List<SlaInstanceView> all = instances(DEMO, incident.ticket);
        assertThat(all).filteredOn(i -> i.policyId().equals(vip)).allMatch(i -> i.state().equals("cancelled"));
        SlaInstanceView resolution = all.stream()
                .filter(i -> i.policyId().equals(general) && i.metric().equals("resolution")).findFirst().orElseThrow();
        assertThat(resolution.startedAt()).isEqualTo(T10);
        assertThat(resolution.dueAt()).isEqualTo(T10.plusSeconds(480 * 60));
    }

    @Test
    void sla02_olaStartsWhenGroupAssignedAndStopsOnReassign() {
        UUID service = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID ola = policy(DEMO, "OLA 2-й линии", "ola", 10, service, c -> new SlaCommands.ConditionsInput(
                null, null, List.of(service), null, List.of(group)), 30, 120);

        Facts f = new Facts(UUID.randomUUID(), service).at(T10);
        apply(DEMO, f, T10);
        assertThat(instances(DEMO, f.ticket)).noneMatch(i -> i.policyId().equals(ola));

        Instant assigned = T10.plusSeconds(600);
        apply(DEMO, f.at(assigned).group(group).status("assigned", "assigned"), assigned);
        SlaInstanceView olaRes = instances(DEMO, f.ticket).stream()
                .filter(i -> i.policyId().equals(ola) && i.metric().equals("resolution")).findFirst().orElseThrow();
        assertThat(olaRes.startedAt()).isEqualTo(assigned);
        assertThat(olaRes.dueAt()).isEqualTo(assigned.plusSeconds(7200));

        apply(DEMO, f.at(T10.plusSeconds(1200)).group(UUID.randomUUID()), T10.plusSeconds(1200));
        assertThat(instances(DEMO, f.ticket)).filteredOn(i -> i.policyId().equals(ola))
                .allMatch(i -> i.state().equals("cancelled"));
    }

    // ---- SLA-04: паузы ----

    @Test
    void sla04_pauseByStatusWithReasonAndResume() {
        UUID service = UUID.randomUUID();
        policy(DEMO, "Пауза", "sla", 10, service, null, 60, 240);
        Facts f = new Facts(UUID.randomUUID(), service).at(T10);
        apply(DEMO, f, T10);

        Instant pausedAt = T10.plusSeconds(3600);
        apply(DEMO, f.at(pausedAt).status("waiting_customer", "pending").reason("waiting_customer: Ждём лог"),
                pausedAt);
        SlaInstanceView paused = resolution(DEMO, f.ticket);
        assertThat(paused.state()).isEqualTo("paused");
        assertThat(paused.dueAt()).isNull();
        assertThat(paused.pausedSince()).isEqualTo(pausedAt);

        Instant resumed = T10.plusSeconds(3 * 3600);
        apply(DEMO, f.at(resumed).status("in_progress", "in_progress").reason(null), resumed);
        SlaInstanceView running = resolution(DEMO, f.ticket);
        assertThat(running.state()).isEqualTo("running");
        // 4 ч цели + 2 ч паузы.
        assertThat(running.dueAt()).isEqualTo(T10.plusSeconds(6 * 3600));

        SlaViews.InstanceDetail detail = call(DEMO, () -> queryService.detail(running.id()));
        assertThat(detail.pauses()).singleElement().satisfies(p -> {
            assertThat(p.reason()).isEqualTo("waiting_customer: Ждём лог");
            assertThat(p.startedAt()).isEqualTo(pausedAt);
            assertThat(p.endedAt()).isEqualTo(resumed);
        });
        assertThat(detail.history()).extracting(SlaViews.HistoryItem::type).contains("started", "paused", "resumed");
    }

    @Test
    void sla04_resolvedPeriodIsExcludedAfterReopen() {
        UUID service = UUID.randomUUID();
        policy(DEMO, "Возврат", "sla", 10, service, null, 60, 240);
        Facts f = new Facts(UUID.randomUUID(), service).at(T10);
        apply(DEMO, f, T10);
        Instant resolved = T10.plusSeconds(3600);
        apply(DEMO, f.at(resolved).status("resolved", "resolved").responded(resolved).resolved(resolved), resolved);
        assertThat(resolution(DEMO, f.ticket).state()).isEqualTo("completed");
        assertThat(instances(DEMO, f.ticket)).filteredOn(i -> i.metric().equals("response"))
                .singleElement().satisfies(i -> assertThat(i.stoppedAt()).isEqualTo(resolved));

        Instant reopened = T10.plusSeconds(4 * 3600);
        apply(DEMO, f.at(reopened).status("in_progress", "in_progress").resolved(null), reopened);
        SlaInstanceView r = resolution(DEMO, f.ticket);
        assertThat(r.state()).isEqualTo("running");
        // 1 ч до решения, 3 ч в «Решена» не считаются, остаток 3 ч от возврата.
        assertThat(r.dueAt()).isEqualTo(reopened.plusSeconds(3 * 3600));
    }

    @Test
    void approvalAndDraftDoNotCount() {
        UUID service = UUID.randomUUID();
        policy(DEMO, "Запросы", "sla", 10, service, null, 60, 240);
        Facts f = new Facts(UUID.randomUUID(), service).at(T10).type("request").status("pending_approval", "approval");
        apply(DEMO, f, T10);
        assertThat(instances(DEMO, f.ticket)).isEmpty();

        Instant approved = T10.plusSeconds(2 * 3600);
        apply(DEMO, f.at(approved).status("approved", "new"), approved);
        assertThat(resolution(DEMO, f.ticket).startedAt()).isEqualTo(approved);
    }

    @Test
    void staleSnapshotIsIgnored() {
        UUID service = UUID.randomUUID();
        policy(DEMO, "Порядок", "sla", 10, service, null, 60, 240);
        Facts f = new Facts(UUID.randomUUID(), service).at(T10);
        Instant pausedAt = T10.plusSeconds(1800);
        // Событие ожидания пришло раньше события создания.
        apply(DEMO, f.at(pausedAt).status("waiting", "pending").reason("waiting"), pausedAt);
        apply(DEMO, f.at(T10).status("new", "new").reason(null), pausedAt);
        SlaInstanceView r = resolution(DEMO, f.ticket);
        assertThat(r.startedAt()).isEqualTo(T10);
        assertThat(r.state()).isEqualTo("paused");
        assertThat(r.pausedSince()).isEqualTo(pausedAt);
    }

    // ---- SLA-05: пороги и нарушения ----

    @Test
    void sla05_warningsAt50_75_90AndExactBreach() {
        UUID service = UUID.randomUUID();
        policy(DEMO, "Пороги", "sla", 10, service, null, 480, 480);
        Facts f = new Facts(UUID.randomUUID(), service).at(T10);
        apply(DEMO, f, T10);
        events.clear();

        // Обработчик проснулся в 17:00: пороги 50% (14:00) и 75% (16:00) уже пройдены, 90% (17:12) — нет.
        job.runOnce(Instant.parse("2026-11-05T17:00:00Z"));
        assertThat(warnings(f.ticket, "resolution")).extracting(SlaWarning::percent, SlaWarning::reachedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(50, Instant.parse("2026-11-05T14:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple(75, Instant.parse("2026-11-05T16:00:00Z")));
        assertThat(resolution(DEMO, f.ticket).breachedAt()).isNull();

        // Обработчик опоздал на полтора часа: время нарушения всё равно точное — 18:00.
        job.runOnce(Instant.parse("2026-11-05T19:30:00Z"));
        assertThat(warnings(f.ticket, "resolution")).extracting(SlaWarning::percent).containsExactly(50, 75, 90);
        assertThat(warnings(f.ticket, "resolution").getLast().reachedAt())
                .isEqualTo(Instant.parse("2026-11-05T17:12:00Z"));
        List<SlaBreached> breaches = events.stream(SlaBreached.class)
                .filter(b -> b.ticketId().equals(f.ticket) && b.metric().equals("resolution")).toList();
        assertThat(breaches).singleElement().satisfies(b -> {
            assertThat(b.breachedAt()).isEqualTo(Instant.parse("2026-11-05T18:00:00Z"));
            assertThat(b.actions()).containsExactly("notify:assignee", "notify:group_lead");
            assertThat(b.tenantId()).isEqualTo(DEMO);
        });

        // Повторный проход событий не дублирует.
        job.runOnce(Instant.parse("2026-11-05T20:00:00Z"));
        assertThat(events.stream(SlaBreached.class).filter(b -> b.ticketId().equals(f.ticket)
                && b.metric().equals("resolution"))).hasSize(1);
    }

    @Test
    void sla05_breachDetectedOnLateResolution() {
        UUID service = UUID.randomUUID();
        policy(DEMO, "Позднее решение", "sla", 10, service, null, 60, 60);
        Facts f = new Facts(UUID.randomUUID(), service).at(T10);
        apply(DEMO, f, T10);
        Instant resolved = T10.plusSeconds(2 * 3600);
        apply(DEMO, f.at(resolved).status("resolved", "resolved").responded(resolved).resolved(resolved), resolved);
        SlaInstanceView r = resolution(DEMO, f.ticket);
        assertThat(r.state()).isEqualTo("completed");
        assertThat(r.breachedAt()).isEqualTo(T10.plusSeconds(3600));
        assertThat(r.elapsedSeconds()).isEqualTo(7200);
    }

    // ---- SLA-06: пересчёт при смене приоритета с историей ----

    @Test
    void sla06_priorityChangeRecalculatesFromStartAndKeepsHistory() {
        UUID service = UUID.randomUUID();
        UUID schedule = call(DEMO, () -> admin.createSchedule(new SlaCommands.ScheduleCreate(
                "Офис МСК", "Europe/Moscow", officeWeek())).id());
        UUID p = call(DEMO, () -> admin.createPolicy(new SlaCommands.PolicyCreate(
                "По приоритетам", null, "sla", true, 10, schedule,
                new SlaCommands.ConditionsInput(null, null, List.of(service), null, null), null, List.of(
                target("resolution", "P1", 4, "hours", "24x7"),
                target("resolution", "P3", 2, "business_days", "policy"),
                target("resolution", "P4", 5, "business_days", "policy")))).id());

        Instant created = msk("2026-11-05T10:00");
        Facts f = new Facts(UUID.randomUUID(), service).at(created).priority("P3");
        apply(DEMO, f, created);
        assertDue(DEMO, f.ticket, "resolution", msk("2026-11-09T10:00"));

        Instant raised = msk("2026-11-05T11:00");
        apply(DEMO, f.at(raised).priority("P1"), raised);
        SlaInstanceView r = resolution(DEMO, f.ticket);
        assertThat(r.policyId()).isEqualTo(p);
        assertThat(r.priority()).isEqualTo("P1");
        assertThat(r.startedAt()).isEqualTo(created);
        assertThat(r.dueAt()).isEqualTo(msk("2026-11-05T14:00"));

        SlaViews.InstanceDetail d = call(DEMO, () -> queryService.detail(r.id()));
        assertThat(d.history()).filteredOn(h -> h.type().equals("recalculated")).singleElement().satisfies(h -> {
            assertThat(h.from()).isEqualTo("P3");
            assertThat(h.to()).isEqualTo("P1");
            assertThat(h.oldDueAt()).isEqualTo(msk("2026-11-09T10:00"));
            assertThat(h.newDueAt()).isEqualTo(msk("2026-11-05T14:00"));
            assertThat(h.occurredAt()).isEqualTo(raised);
        });

        // P1 нарушен в 14:00, затем приоритет понижен до P4: нарушения по новой цели нет, оно снимается.
        events.clear();
        job.runOnce(msk("2026-11-05T15:00"));
        assertThat(resolution(DEMO, f.ticket).breachedAt()).isEqualTo(msk("2026-11-05T14:00"));
        apply(DEMO, f.at(msk("2026-11-05T15:30")).priority("P4"), msk("2026-11-05T15:30"));
        SlaInstanceView lowered = resolution(DEMO, f.ticket);
        assertThat(lowered.breachedAt()).isNull();
        // 5 рабочих дней = 45 ч от чт 10:00: чт 8, пт 9, пн–ср 27 → 44 ч, ещё 1 ч → чт 12.11 10:00.
        assertThat(lowered.dueAt()).isEqualTo(msk("2026-11-12T10:00"));
    }

    @Test
    void scheduleChangeTriggersRecalculation() {
        UUID service = UUID.randomUUID();
        SlaViews.Schedule s = call(DEMO, () -> admin.createSchedule(
                new SlaCommands.ScheduleCreate("Офис", "Europe/Moscow", officeWeek())));
        TenantContext.runAs(DEMO, () -> admin.createPolicy(new SlaCommands.PolicyCreate("По графику", null, "sla",
                true, 10, s.id(), new SlaCommands.ConditionsInput(null, null, List.of(service), null, null), null,
                List.of(target("resolution", "P3", 4, "hours", "policy")))));
        Instant created = msk("2026-11-05T16:00");
        Facts f = new Facts(UUID.randomUUID(), service).at(created).priority("P3");
        apply(DEMO, f, created);
        assertDue(DEMO, f.ticket, "resolution", msk("2026-11-06T11:00"));

        // Пятница 06.11 объявлена нерабочей: срок переносится на понедельник.
        TenantContext.runAs(DEMO, () -> admin.replaceHolidays(s.id(), 2026, List.of(
                new SlaCommands.HolidayInput(java.time.LocalDate.parse("2026-11-06"), "Перенос", List.of()))));
        job.runOnce(Instant.now());
        assertDue(DEMO, f.ticket, "resolution", msk("2026-11-09T11:00"));
    }

    // ---- вспомогательное ----

    static java.util.Map<java.time.DayOfWeek, List<String>> officeWeek() {
        var w = new java.util.EnumMap<java.time.DayOfWeek, List<String>>(java.time.DayOfWeek.class);
        for (var d : java.time.DayOfWeek.values()) {
            w.put(d, d.getValue() <= 5 ? List.of("09:00-18:00") : List.of());
        }
        return w;
    }

    static SlaCommands.TargetInput target(String metric, String priority, int amount, String unit, String cal) {
        return new SlaCommands.TargetInput(metric, priority, amount, unit, cal, null, List.of("notify:assignee"),
                List.of("notify:assignee", "notify:group_lead"));
    }

    /** Политика 24×7 для приоритета P2 с целями реакции и решения в минутах. */
    UUID policy(UUID tenant, String name, String kind, int rank, UUID service,
                UnaryOperator<SlaCommands.ConditionsInput> conditions, int responseMin, int resolutionMin) {
        SlaCommands.ConditionsInput c = new SlaCommands.ConditionsInput(null, null, List.of(service), null, null);
        SlaCommands.ConditionsInput cond = conditions == null ? c : conditions.apply(c);
        return call(tenant, () -> admin.createPolicy(new SlaCommands.PolicyCreate(
                name, null, kind, true, rank, null, cond, null, List.of(
                target("response", "P2", responseMin, "minutes", "24x7"),
                target("resolution", "P2", resolutionMin, "minutes", "24x7")))).id());
    }

    void apply(UUID tenant, Facts f, Instant now) {
        TenantContext.runAs(tenant, () -> tracker.apply(f.build(), now));
    }

    List<SlaInstanceView> instances(UUID tenant, UUID ticket) {
        return call(tenant, () -> queries.forTicket(ticket));
    }

    SlaInstanceView resolution(UUID tenant, UUID ticket) {
        return instances(tenant, ticket).stream()
                .filter(i -> i.metric().equals("resolution") && !i.state().equals("cancelled"))
                .reduce((a, b) -> b).orElseThrow();
    }

    void assertDue(UUID tenant, UUID ticket, String metric, Instant expected) {
        SlaInstanceView v = instances(tenant, ticket).stream()
                .filter(i -> i.metric().equals(metric) && !i.state().equals("cancelled"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(v.dueAt()).as("%s срок", metric).isEqualTo(expected);
    }

    List<SlaWarning> warnings(UUID ticket, String metric) {
        return events.stream(SlaWarning.class).filter(w -> w.ticketId().equals(ticket) && w.metric().equals(metric))
                .toList();
    }

    static <T> T call(UUID tenant, java.util.concurrent.Callable<T> action) {
        try {
            return TenantContext.callAs(tenant, action);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Instant msk(String local) {
        return java.time.LocalDateTime.parse(local).atZone(MSK).toInstant();
    }

    /** Изменяемый снимок заявки для сценариев. */
    static final class Facts {
        final UUID ticket;
        final UUID service;
        Instant occurredAt;
        Instant createdAt;
        String type = "incident";
        String priority = "P2";
        UUID client;
        UUID group;
        String statusCode = "new";
        String category = "new";
        String reason;
        Instant responded;
        Instant resolved;

        Facts(UUID ticket, UUID service) {
            this.ticket = ticket;
            this.service = service;
        }

        Facts at(Instant t) {
            occurredAt = t;
            if (createdAt == null) {
                createdAt = t;
            }
            return this;
        }

        Facts priority(String p) {
            priority = p;
            return this;
        }

        Facts type(String t) {
            type = t;
            return this;
        }

        Facts client(UUID c) {
            client = c;
            return this;
        }

        Facts group(UUID g) {
            group = g;
            return this;
        }

        Facts status(String code, String cat) {
            statusCode = code;
            category = cat;
            return this;
        }

        Facts reason(String r) {
            reason = r;
            return this;
        }

        Facts responded(Instant t) {
            responded = t;
            return this;
        }

        Facts resolved(Instant t) {
            resolved = t;
            return this;
        }

        TicketFacts build() {
            return new TicketFacts(ticket, occurredAt, createdAt, type, priority, service, client, group, statusCode,
                    category, null, reason, responded, resolved);
        }
    }
}
