package ru.easyitsm.changes.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.changes.api.ChangeState;
import ru.easyitsm.changes.api.RiskLevel;

/**
 * Календарь изменений (CHG-05, 10.10): окна изменений в интервале и заморозки, фильтры по услуге, группе, риску и
 * статусу; выгрузка в iCalendar.
 */
@RestController
class CalendarController {

    /** Самый длинный интервал одного запроса: месяц с запасом на соседние недели. */
    static final Duration MAX_RANGE = Duration.ofDays(100);

    private final ChangeStore store;
    private final ChangeService service;
    private final FreezeStore freezes;

    CalendarController(ChangeStore store, ChangeService service, FreezeStore freezes) {
        this.store = store;
        this.service = service;
        this.freezes = freezes;
    }

    @GetMapping("/api/v1/changes/calendar")
    @PreAuthorize(ChangeController.READERS)
    @Transactional(readOnly = true)
    ChangeDtos.Calendar calendar(@RequestParam Instant from, @RequestParam Instant to,
                                 @RequestParam(required = false) UUID serviceId,
                                 @RequestParam(required = false) UUID groupId,
                                 @RequestParam(required = false) String risk,
                                 @RequestParam(required = false) String state,
                                 @RequestParam(required = false) UUID ciId) {
        return load(from, to, serviceId, groupId, risk, state, ciId);
    }

    @GetMapping("/api/v1/changes/calendar.ics")
    @PreAuthorize(ChangeController.READERS)
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> ics(@RequestParam Instant from, @RequestParam Instant to,
                               @RequestParam(required = false) UUID serviceId,
                               @RequestParam(required = false) UUID groupId,
                               @RequestParam(required = false) String risk,
                               @RequestParam(required = false) String state) {
        ChangeDtos.Calendar cal = load(from, to, serviceId, groupId, risk, state, null);
        return file(ICal.write(cal.changes(), cal.freezes(), null), "changes.ics");
    }

    @GetMapping("/api/v1/changes/{id}/ical")
    @PreAuthorize(ChangeController.READERS)
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> one(@PathVariable UUID id) {
        ChangeRow c = service.readable(id, Me.current());
        return file(ICal.write(List.of(ChangeDtos.ChangeItem.of(c)), List.of(), null), c.number + ".ics");
    }

    private ChangeDtos.Calendar load(Instant from, Instant to, UUID serviceId, UUID groupId, String risk, String state,
                                     UUID ciId) {
        if (!to.isAfter(from)) {
            throw ChangeProblem.field("to", "Конец интервала должен быть позже начала");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ChangeProblem.field("to", "Интервал календаря — не больше 100 дней");
        }
        Me me = Me.current();
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder("where ").append(service.visibility(me, p))
                .append(" and c.window_start < :to and c.window_end > :from");
        p.put("from", Sql.ts(from));
        p.put("to", Sql.ts(to));
        if (serviceId != null) {
            where.append(" and :service = any(c.service_ids)");
            p.put("service", serviceId);
        }
        if (ciId != null) {
            where.append(" and :ci = any(c.ci_ids)");
            p.put("ci", ciId);
        }
        if (groupId != null) {
            where.append(" and c.group_id = :group");
            p.put("group", groupId);
        }
        if (risk != null && !risk.isBlank()) {
            where.append(" and c.risk_level = :risk");
            p.put("risk", RiskLevel.fromCode(risk).code());
        }
        if (state != null && !state.isBlank()) {
            List<String> states = new ArrayList<>();
            for (String s : Arrays.stream(state.split(",")).map(String::strip).toList()) {
                states.add(ChangeState.fromCode(s).code());
            }
            where.append(" and c.state = any(cast(:states as text[]))");
            p.put("states", "{" + String.join(",", states) + "}");
        } else {
            where.append(" and c.state not in ('cancelled', 'rejected')");
        }
        List<ChangeDtos.ChangeItem> changes = store.query(where + " order by c.window_start, c.ticket_id limit 1000", p)
                .stream().map(ChangeDtos.ChangeItem::of).toList();
        List<ChangeDtos.FreezeView> fs = freezes.overlapping(from, to).stream().map(ChangeDtos.FreezeView::of).toList();
        return new ChangeDtos.Calendar(from, to, changes, fs);
    }

    static ResponseEntity<byte[]> file(byte[] body, String name) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
}
