package ru.easyitsm.sla.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Применяет снимок заявки к её счётчикам SLA: старт по подходящим политикам (SLA-01, 02), паузы (SLA-04),
 * остановка при реакции и решении, возврат из «Решена», пересчёт при смене приоритета и политики (SLA-06),
 * затем пороги и нарушения (SLA-05).
 * <p>
 * Снимки одной заявки обрабатываются по очереди: строка {@code sla_ticket} блокируется на время транзакции.
 * Снимок старше уже обработанного отбрасывается, поэтому порядок доставки событий не важен.
 */
@Service
class SlaTracker {

    private final SlaInstanceRepository instances;
    private final SlaInstanceEventRepository journal;
    private final SlaPolicyRepository policies;
    private final Calendars calendars;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    SlaTracker(SlaInstanceRepository instances, SlaInstanceEventRepository journal, SlaPolicyRepository policies,
               Calendars calendars, JdbcClient jdbc, ApplicationEventPublisher events) {
        this.instances = instances;
        this.journal = journal;
        this.policies = policies;
        this.calendars = calendars;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public void apply(TicketFacts f) {
        apply(f, Instant.now());
    }

    /** @param now момент обработки: до него срабатывают пороги и фиксируются нарушения */
    @Transactional
    public void apply(TicketFacts f, Instant now) {
        TicketState ticket = lock(f);
        if (ticket == null) {
            return;
        }
        String category = f.statusCategory();
        Instant countingSince = ticket.countingSince;
        if (countingSince == null && !StatusCategory.NOT_STARTED.contains(category)) {
            countingSince = ticket.firstEvent ? f.createdAt() : f.occurredAt();
        }
        saveState(f, countingSince);

        List<SlaInstance> all = new ArrayList<>(instances.findByTicketIdOrderById(f.ticketId()));
        Map<UUID, SlaPolicy> policyById = new HashMap<>();
        policies.findAll().forEach(p -> policyById.put(p.getId(), p));
        Calendars.Session cals = calendars.session();
        List<SlaInstance> touched = new ArrayList<>();

        switch (category) {
            case StatusCategory.CANCELLED -> all.stream().filter(SlaInstance::live).forEach(i -> {
                i.cancel(f.occurredAt(), "ticket_cancelled");
                touched.add(i);
            });
            case StatusCategory.RESOLVED, StatusCategory.CLOSED -> {
                Instant at = f.resolvedAt() != null ? f.resolvedAt() : f.occurredAt();
                completeResponse(f, all, touched);
                all.stream().filter(SlaInstance::live).forEach(i -> {
                    i.complete(at, category);
                    touched.add(i);
                });
            }
            default -> {
                for (SlaInstance i : all) {
                    if (SlaInstance.COMPLETED.equals(i.state()) && SlaTarget.RESOLUTION.equals(i.metric())) {
                        i.reopen(f.occurredAt());
                        touched.add(i);
                    }
                }
                if (countingSince != null && !StatusCategory.NOT_STARTED.contains(category)) {
                    Instant freshStart = ticket.firstEvent ? countingSince
                            : max(countingSince, f.occurredAt());
                    match(f, all, policyById, cals, freshStart, now, touched);
                }
                for (SlaInstance i : all) {
                    if (!i.live()) {
                        continue;
                    }
                    SlaPolicy p = policyById.get(i.policyId());
                    boolean pause = StatusCategory.NOT_STARTED.contains(category)
                            || (p != null && p.pausesOn(f.statusCode(), category));
                    if (pause && SlaInstance.RUNNING.equals(i.state())) {
                        i.pause(f.statusSince(), f.pauseReason() != null ? f.pauseReason() : f.statusCode());
                        touched.add(i);
                    } else if (!pause && SlaInstance.PAUSED.equals(i.state())) {
                        i.resume(f.statusSince());
                        touched.add(i);
                    }
                }
                completeResponse(f, all, touched);
            }
        }

        for (SlaInstance i : touched.stream().distinct().toList()) {
            i.evaluate(cals.of(i.scheduleId()), now);
            persist(i);
        }
    }

    /** Подбирает политику каждого вида и приводит счётчики к ней. */
    private void match(TicketFacts f, List<SlaInstance> all, Map<UUID, SlaPolicy> policyById, Calendars.Session cals,
                       Instant freshStart, Instant now, List<SlaInstance> touched) {
        List<SlaPolicy> ordered = policyById.values().stream()
                .sorted(Comparator.comparingInt(SlaPolicy::rank).thenComparing(SlaPolicy::getId))
                .toList();
        for (String kind : SlaPolicy.KINDS) {
            SlaPolicy policy = ordered.stream().filter(p -> p.kind().equals(kind) && p.matches(f)).findFirst()
                    .orElse(null);
            Map<String, SlaInstance> replaced = new HashMap<>();
            for (SlaInstance i : List.copyOf(all)) {
                if (i.kind().equals(kind) && i.live() && (policy == null || !i.policyId().equals(policy.getId()))) {
                    i.cancel(f.occurredAt(), "policy_changed");
                    replaced.put(i.metric(), i);
                    touched.add(i);
                }
            }
            if (policy == null) {
                continue;
            }
            List<SlaTarget> targets = policy.targetsFor(f.priority());
            for (SlaInstance i : all) {
                if (i.live() && i.policyId().equals(policy.getId())
                        && targets.stream().noneMatch(t -> t.metric().equals(i.metric()))) {
                    i.cancel(f.occurredAt(), "no_target");
                    touched.add(i);
                }
            }
            for (SlaTarget t : targets) {
                UUID scheduleId = t.aroundTheClock() ? null : policy.scheduleId();
                BusinessCalendar cal = cals.of(scheduleId);
                long targetMs = t.targetMs(cal);
                Optional<SlaInstance> existing = all.stream()
                        .filter(i -> i.policyId().equals(policy.getId()) && i.metric().equals(t.metric())
                                && !SlaInstance.CANCELLED.equals(i.state()))
                        .reduce((a, b) -> b);
                if (existing.isPresent()) {
                    SlaInstance i = existing.get();
                    if (i.live() && (!i.priority().equals(f.priority()) || i.targetMs() != targetMs
                            || !java.util.Objects.equals(i.scheduleId(), scheduleId))) {
                        i.retarget(t, f.priority(), scheduleId, targetMs, cals.of(i.scheduleId()), cal,
                                f.occurredAt(), now, "priority_changed");
                        touched.add(i);
                    }
                    continue;
                }
                SlaInstance inherit = replaced.get(t.metric());
                if (SlaTarget.RESPONSE.equals(t.metric()) && f.respondedAt() != null && inherit == null) {
                    continue;
                }
                SlaInstance created = SlaInstance.start(f.ticketId(), policy, t, f.priority(), scheduleId, targetMs,
                        freshStart, inherit);
                all.add(created);
                touched.add(created);
            }
        }
    }

    private static void completeResponse(TicketFacts f, List<SlaInstance> all, List<SlaInstance> touched) {
        if (f.respondedAt() == null) {
            return;
        }
        for (SlaInstance i : all) {
            if (i.live() && SlaTarget.RESPONSE.equals(i.metric())) {
                i.complete(f.respondedAt(), "responded");
                touched.add(i);
            }
        }
    }

    /** Пересчитывает счётчик фоновым обработчиком: пороги, нарушение, новый срок после смены графика. */
    @Transactional
    public void check(UUID instanceId, Instant now) {
        instances.findById(instanceId).ifPresent(i -> {
            i.evaluate(calendars.load(i.scheduleId()), now);
            persist(i);
        });
    }

    private void persist(SlaInstance i) {
        List<SlaInstanceEvent> log = i.drainJournal();
        List<Object> out = i.drainOutbox();
        instances.save(i);
        journal.saveAll(log);
        out.forEach(events::publishEvent);
    }

    private TicketState lock(TicketFacts f) {
        UUID tenant = TenantContext.require();
        jdbc.sql("""
                        insert into sla_ticket (tenant_id, ticket_id) values (:tenant, :ticket)
                        on conflict (tenant_id, ticket_id) do nothing""")
                .param("tenant", tenant).param("ticket", f.ticketId()).update();
        TicketState s = jdbc.sql("""
                        select last_event_at, counting_since from sla_ticket
                         where tenant_id = :tenant and ticket_id = :ticket for update""")
                .param("tenant", tenant).param("ticket", f.ticketId())
                .query((rs, n) -> new TicketState(
                        toInstant(rs.getObject("last_event_at", OffsetDateTime.class)),
                        toInstant(rs.getObject("counting_since", OffsetDateTime.class))))
                .single();
        if (s.lastEventAt != null && f.occurredAt().isBefore(s.lastEventAt)) {
            return null;
        }
        return s;
    }

    private void saveState(TicketFacts f, Instant countingSince) {
        jdbc.sql("""
                        update sla_ticket set last_event_at = :at, counting_since = :since, updated_at = now()
                         where tenant_id = :tenant and ticket_id = :ticket""")
                .param("at", OffsetDateTime.ofInstant(f.occurredAt(), ZoneOffset.UTC))
                .param("since", countingSince == null ? null : OffsetDateTime.ofInstant(countingSince, ZoneOffset.UTC))
                .param("tenant", TenantContext.require()).param("ticket", f.ticketId())
                .update();
    }

    private static Instant toInstant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static final class TicketState {
        final Instant lastEventAt;
        final Instant countingSince;
        final boolean firstEvent;

        TicketState(Instant lastEventAt, Instant countingSince) {
            this.lastEventAt = lastEventAt;
            this.countingSince = countingSince;
            this.firstEvent = lastEventAt == null;
        }
    }
}
