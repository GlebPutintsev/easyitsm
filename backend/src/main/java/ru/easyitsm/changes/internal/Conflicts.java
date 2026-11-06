package ru.easyitsm.changes.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Конфликты окна (CHG-05, AT-06): другие незавершённые изменения, чьё окно пересекается с этим и которые
 * затрагивают ту же КЕ или услугу, и периоды заморозки, под которые попадает окно.
 */
@Component
class Conflicts {

    private final ChangeStore changes;
    private final FreezeStore freezes;

    Conflicts(ChangeStore changes, FreezeStore freezes) {
        this.changes = changes;
        this.freezes = freezes;
    }

    List<ChangeDtos.Conflict> find(Instant start, Instant end, List<UUID> cis, List<UUID> services, UUID excludeId) {
        if (start == null || end == null || !end.isAfter(start)) {
            return List.of();
        }
        List<ChangeDtos.Conflict> result = new ArrayList<>();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("start", Sql.ts(start));
        p.put("end", Sql.ts(end));
        p.put("exclude", excludeId);
        p.put("cis", Sql.array(cis));
        p.put("services", Sql.array(services));
        List<ChangeRow> overlapping = changes.query("""
                where c.window_start < :end and c.window_end > :start
                  and c.state not in ('closed', 'rejected', 'cancelled')
                  and (cast(:exclude as uuid) is null or c.ticket_id <> cast(:exclude as uuid))
                  and (c.ci_ids && cast(:cis as uuid[]) or c.service_ids && cast(:services as uuid[]))
                order by c.window_start, c.ticket_id limit 100""", p);
        for (ChangeRow c : overlapping) {
            List<UUID> sharedCis = c.ciIds.stream().filter(cis::contains).toList();
            List<UUID> sharedServices = c.serviceIds.stream().filter(services::contains).toList();
            Instant from = c.windowStart.isAfter(start) ? c.windowStart : start;
            Instant to = c.windowEnd.isBefore(end) ? c.windowEnd : end;
            result.add(new ChangeDtos.Conflict(sharedCis.isEmpty() ? "service" : "ci", c.id, c.number, c.title,
                    c.state.code(), null, sharedCis, sharedServices, from, to));
        }
        for (FreezeStore.Freeze f : freezes.overlapping(start, end)) {
            if (f.covers(cis, services)) {
                Instant from = f.startsAt().isAfter(start) ? f.startsAt() : start;
                Instant to = f.endsAt().isBefore(end) ? f.endsAt() : end;
                result.add(new ChangeDtos.Conflict("freeze", null, null, f.title(), null, f.id(),
                        f.ciIds().stream().filter(cis::contains).toList(),
                        f.serviceIds().stream().filter(services::contains).toList(), from, to));
            }
        }
        return result;
    }

    static boolean hasFreeze(List<ChangeDtos.Conflict> conflicts) {
        return conflicts.stream().anyMatch(c -> "freeze".equals(c.kind()));
    }

    static boolean hasChangeConflicts(List<ChangeDtos.Conflict> conflicts) {
        return conflicts.stream().anyMatch(c -> !"freeze".equals(c.kind()));
    }
}
