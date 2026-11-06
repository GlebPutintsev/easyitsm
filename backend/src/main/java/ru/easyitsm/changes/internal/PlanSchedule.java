package ru.easyitsm.changes.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Время шагов плана (CHG-03): подготовка начинается с {@code prepStart}, внедрение — с начала окна, проверка —
 * после внедрения. Шаг с явным временем начала сдвигает цепочку, следующие идут за ним. План возврата — только
 * длительности: время его запуска заранее неизвестно.
 */
final class PlanSchedule {

    private PlanSchedule() {
    }

    record Result(List<ChangeDtos.StepView> steps, ChangeDtos.PlanTotals totals) {
    }

    static Result compute(ChangeRow c, List<ChangeStore.Step> steps) {
        List<ChangeDtos.StepView> views = new ArrayList<>(steps.size());
        int[] minutes = new int[4];
        Instant prepCursor = c.prepStart;
        Instant cursor = c.windowStart;
        Instant planEnd = null;
        for (ChangeStore.Step s : steps) {
            int phase = ChangeStore.PHASES.indexOf(s.phase());
            minutes[phase] += s.durationMinutes();
            Instant start = null;
            Instant end = null;
            switch (s.phase()) {
                case "prepare" -> {
                    start = s.plannedStart() != null ? s.plannedStart() : prepCursor;
                    if (start != null) {
                        end = start.plus(Duration.ofMinutes(s.durationMinutes()));
                        prepCursor = end;
                    }
                }
                case "implement", "verify" -> {
                    start = s.plannedStart() != null ? s.plannedStart() : cursor;
                    if (start != null) {
                        end = start.plus(Duration.ofMinutes(s.durationMinutes()));
                        cursor = end;
                        planEnd = end;
                    }
                }
                default -> {
                }
            }
            views.add(new ChangeDtos.StepView(s.id(), s.phase(), s.seq(), s.title(), s.description(), s.ownerId(),
                    s.ownerName(), s.team(), s.contacts(), s.plannedStart(), start, end, s.durationMinutes(), s.upto(),
                    s.interruption(), s.checkCriteria(), s.rollbackRef(), s.status(), s.resultNote(), s.executedAt(),
                    s.executedBy()));
        }
        Boolean fits = c.windowStart == null || c.windowEnd == null || planEnd == null ? null
                : !planEnd.isAfter(c.windowEnd);
        return new Result(views, new ChangeDtos.PlanTotals(minutes[0], minutes[1], minutes[2], minutes[3], planEnd,
                fits));
    }
}
