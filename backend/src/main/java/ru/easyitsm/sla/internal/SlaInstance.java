package ru.easyitsm.sla.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.sla.api.SlaInstanceView;
import ru.easyitsm.sla.events.SlaBreached;
import ru.easyitsm.sla.events.SlaWarning;

/**
 * Счётчик SLA по заявке: одна цель (показатель) одной политики.
 * <p>
 * Хранит момент старта, цель и журнал исключённых интервалов. Срок, пороги и нарушение не накапливаются
 * по шагам, а каждый раз вычисляются заново от старта по календарю ({@link #evaluate}). Поэтому пересчёт
 * после смены приоритета, графика или праздников точен и не копит ошибку (NFR-05).
 */
@Table("sla_instance")
class SlaInstance extends TenantEntity {

    static final String RUNNING = "running";
    static final String PAUSED = "paused";
    static final String COMPLETED = "completed";
    static final String CANCELLED = "cancelled";

    private UUID ticketId;
    private UUID policyId;
    private String kind;
    private String metric;
    private String priority;
    /** График расчёта; {@code null} — круглосуточно. */
    private UUID scheduleId;
    private long targetMs;
    private List<Integer> thresholds = new ArrayList<>();
    private List<String> warnActions = new ArrayList<>();
    private List<String> breachActions = new ArrayList<>();
    private String state;
    private Instant startedAt;
    private Instant stoppedAt;
    private Instant dueAt;
    private Instant breachedAt;
    private Instant nextCheckAt;
    private List<Integer> firedThresholds = new ArrayList<>();
    private String endReason;
    @MappedCollection(idColumn = "instance_id", keyColumn = "seq")
    private List<SlaPause> pauses = new ArrayList<>();

    /** Записи журнала, накопленные за операцию. Сохраняет {@link SlaTracker}. */
    @Transient
    private final List<SlaInstanceEvent> journal = new ArrayList<>();
    /** Доменные события для публикации после сохранения. */
    @Transient
    private final List<Object> outbox = new ArrayList<>();

    protected SlaInstance() {
    }

    private SlaInstance(UUID ticketId, SlaPolicy policy, SlaTarget target, String priority, UUID scheduleId,
                        long targetMs, Instant startedAt) {
        this.ticketId = ticketId;
        this.policyId = policy.getId();
        this.kind = policy.kind();
        this.metric = target.metric();
        this.state = RUNNING;
        this.startedAt = startedAt;
        applyTarget(target, priority, scheduleId, targetMs);
    }

    /**
     * Новый счётчик.
     *
     * @param inherit счётчик того же показателя, который заменяется при смене политики: его старт и паузы
     *                переходят в новый, чтобы уже затраченное время не потерялось
     */
    static SlaInstance start(UUID ticketId, SlaPolicy policy, SlaTarget target, String priority, UUID scheduleId,
                             long targetMs, Instant startedAt, SlaInstance inherit) {
        Instant start = inherit != null ? inherit.startedAt : startedAt;
        var i = new SlaInstance(ticketId, policy, target, priority, scheduleId, targetMs, start);
        String reason = "started";
        if (inherit != null) {
            inherit.pauses.forEach(p -> i.pauses.add(p.copy()));
            if (inherit.state.equals(PAUSED) || inherit.state.equals(CANCELLED)) {
                // Отменённый при замене счётчик закрыл паузу моментом замены: продолжаем её в новом.
                SlaPause last = i.pauses.isEmpty() ? null : i.pauses.getLast();
                if (last != null && inherit.stoppedAt != null && inherit.stoppedAt.equals(last.endedAt())
                        && SlaPause.PAUSE.equals(last.kind())) {
                    i.pauses.set(i.pauses.size() - 1, new SlaPause(last.kind(), last.startedAt(), null,
                            last.reason()));
                    i.state = PAUSED;
                }
            }
            i.firedThresholds.addAll(inherit.firedThresholds.stream().filter(i.thresholds::contains).toList());
            reason = "inherited";
        }
        i.log("started", start, reason, null, priority, null, null);
        return i;
    }

    boolean live() {
        return RUNNING.equals(state) || PAUSED.equals(state);
    }

    void pause(Instant at, String reason) {
        if (!RUNNING.equals(state)) {
            return;
        }
        Instant from = clamp(at);
        pauses.add(new SlaPause(SlaPause.PAUSE, from, null, reason));
        state = PAUSED;
        log("paused", from, reason, null, null, null, null);
    }

    void resume(Instant at) {
        if (!PAUSED.equals(state)) {
            return;
        }
        closeOpenPause(at);
        state = RUNNING;
        log("resumed", at, null, null, null, null, null);
    }

    /** Цель достигнута: реакция дана или заявка решена. */
    void complete(Instant at, String reason) {
        if (!live()) {
            return;
        }
        closeOpenPause(at);
        state = COMPLETED;
        stoppedAt = clamp(at);
        endReason = reason;
        log("completed", stoppedAt, reason, null, null, null, null);
    }

    void cancel(Instant at, String reason) {
        if (!live()) {
            return;
        }
        closeOpenPause(at);
        state = CANCELLED;
        stoppedAt = clamp(at);
        endReason = reason;
        nextCheckAt = null;
        log("cancelled", stoppedAt, reason, null, null, null, null);
    }

    /** Заявка возвращена из «Решена» в работу: время в решённом состоянии не считается. */
    void reopen(Instant at) {
        if (!COMPLETED.equals(state) || !"resolved".equals(endReason)) {
            return;
        }
        Instant to = at.isBefore(stoppedAt) ? stoppedAt : at;
        pauses.add(new SlaPause(SlaPause.RESOLVED, stoppedAt, to, "reopened"));
        state = RUNNING;
        stoppedAt = null;
        endReason = null;
        log("reopened", to, "reopened", null, null, null, null);
    }

    /**
     * Пересчёт при смене приоритета (SLA-06): новая цель применяется от исходного старта, старый и новый срок
     * записываются в журнал. Нарушение, которое по новой цели ещё не наступило, снимается.
     */
    void retarget(SlaTarget target, String newPriority, UUID newScheduleId, long newTargetMs, BusinessCalendar cal,
                  Instant at, Instant now, String reason) {
        String oldPriority = priority;
        Instant oldDue = dueAt;
        applyTarget(target, newPriority, newScheduleId, newTargetMs);
        Gaps gaps = gaps();
        long bound = bound(now);
        Long due = cal.addMs(startedAt.toEpochMilli(), targetMs, gaps);
        firedThresholds.removeIf(p -> {
            Long t = cal.addMs(startedAt.toEpochMilli(), thresholdMs(p), gaps);
            return !thresholds.contains(p) || t == null || t > bound;
        });
        if (breachedAt != null) {
            breachedAt = due != null && due <= bound ? Instant.ofEpochMilli(due) : null;
        }
        dueAt = due == null ? null : Instant.ofEpochMilli(due);
        log("recalculated", at, reason, oldPriority, newPriority, oldDue, dueAt);
    }

    /**
     * Вычисляет срок, срабатывает пороги и фиксирует нарушение на момент {@code now}, назначает следующую проверку.
     * Времена порогов и нарушения точные (из календаря), а не время, когда их заметил обработчик.
     */
    void evaluate(BusinessCalendar cal, Instant now) {
        if (CANCELLED.equals(state)) {
            nextCheckAt = null;
            return;
        }
        Gaps gaps = gaps();
        long start = startedAt.toEpochMilli();
        long bound = bound(now);
        Long due = cal.addMs(start, targetMs, gaps);
        dueAt = due == null ? null : Instant.ofEpochMilli(due);

        long next = Long.MAX_VALUE;
        for (int p : thresholds) {
            if (firedThresholds.contains(p)) {
                continue;
            }
            Long t = cal.addMs(start, thresholdMs(p), gaps);
            if (t != null && t <= bound) {
                firedThresholds.add(p);
                if (live()) {
                    Instant reached = Instant.ofEpochMilli(t);
                    outbox.add(new SlaWarning(getTenantId(), ticketId, getId(), policyId, kind, metric, p, reached,
                            dueAt, List.copyOf(warnActions)));
                    log("warning", reached, null, null, Integer.toString(p), null, dueAt);
                }
            } else if (t != null) {
                next = Math.min(next, t);
            }
        }
        if (breachedAt == null && due != null && due <= bound) {
            breachedAt = dueAt;
            outbox.add(new SlaBreached(getTenantId(), ticketId, getId(), policyId, kind, metric, priority, breachedAt,
                    List.copyOf(breachActions)));
            log("breached", breachedAt, null, null, null, null, dueAt);
        }
        if (RUNNING.equals(state)) {
            if (breachedAt == null && due != null) {
                next = Math.min(next, due);
            }
            nextCheckAt = next == Long.MAX_VALUE ? null : Instant.ofEpochMilli(next);
        } else {
            nextCheckAt = null;
        }
    }

    /** Затраченное рабочее время без пауз на момент {@code now}. */
    long elapsedMs(BusinessCalendar cal, Instant now) {
        return cal.elapsedMs(startedAt.toEpochMilli(), bound(now), gaps());
    }

    SlaInstanceView toView(BusinessCalendar cal, Instant now, String policyName) {
        long elapsed = elapsedMs(cal, now);
        Instant pausedSince = PAUSED.equals(state) && !pauses.isEmpty() && pauses.getLast().open()
                ? pauses.getLast().startedAt() : null;
        int percent = targetMs == 0 ? 100 : (int) Math.min(Integer.MAX_VALUE, elapsed * 100 / targetMs);
        return new SlaInstanceView(getId(), ticketId, policyId, policyName, kind, metric, priority, state,
                targetMs / 1000, startedAt, dueAt, stoppedAt, breachedAt, elapsed / 1000, (targetMs - elapsed) / 1000,
                percent, pausedSince);
    }

    private void applyTarget(SlaTarget target, String priority, UUID scheduleId, long targetMs) {
        this.priority = priority;
        this.scheduleId = scheduleId;
        this.targetMs = targetMs;
        this.thresholds = new ArrayList<>(target.thresholds());
        this.warnActions = new ArrayList<>(target.warnActions());
        this.breachActions = new ArrayList<>(target.breachActions());
    }

    private long thresholdMs(int percent) {
        return Math.floorDiv(targetMs * percent, 100);
    }

    private long bound(Instant now) {
        long n = now.toEpochMilli();
        return stoppedAt == null ? n : Math.min(n, stoppedAt.toEpochMilli());
    }

    Gaps gaps() {
        List<Long[]> list = new ArrayList<>(pauses.size());
        for (SlaPause p : pauses) {
            list.add(new Long[] {p.startedAt().toEpochMilli(), p.open() ? null : p.endedAt().toEpochMilli()});
        }
        return Gaps.of(list);
    }

    private void closeOpenPause(Instant at) {
        if (!pauses.isEmpty() && pauses.getLast().open()) {
            pauses.getLast().close(at);
        }
    }

    private Instant clamp(Instant at) {
        return at.isBefore(startedAt) ? startedAt : at;
    }

    private void log(String type, Instant at, String reason, String from, String to, Instant oldDue, Instant newDue) {
        journal.add(new SlaInstanceEvent(getId(), ticketId, type, at, reason, from, to, oldDue, newDue));
    }

    List<SlaInstanceEvent> drainJournal() {
        List<SlaInstanceEvent> out = List.copyOf(journal);
        journal.clear();
        return out;
    }

    List<Object> drainOutbox() {
        List<Object> out = List.copyOf(outbox);
        outbox.clear();
        return out;
    }

    UUID ticketId() {
        return ticketId;
    }

    UUID policyId() {
        return policyId;
    }

    String kind() {
        return kind;
    }

    String metric() {
        return metric;
    }

    String priority() {
        return priority;
    }

    String state() {
        return state;
    }

    UUID scheduleId() {
        return scheduleId;
    }

    long targetMs() {
        return targetMs;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant stoppedAt() {
        return stoppedAt;
    }

    Instant dueAt() {
        return dueAt;
    }

    Instant breachedAt() {
        return breachedAt;
    }

    Instant nextCheckAt() {
        return nextCheckAt;
    }

    List<Integer> firedThresholds() {
        return firedThresholds;
    }

    List<SlaPause> pauses() {
        return pauses;
    }
}
