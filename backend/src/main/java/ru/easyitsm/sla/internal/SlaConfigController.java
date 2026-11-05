package ru.easyitsm.sla.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/** Настройка SLA (раздел 10.16 «Управление → SLA»): изменяет ADM, читают ADM, LEAD, ANL, AUD. */
@RestController
@RequestMapping("/api/v1/sla")
class SlaConfigController {

    private static final String READ = "hasAnyRole('ADM', 'LEAD', 'ANL', 'AUD')";
    private static final String WRITE = "hasRole('ADM')";

    private final SlaAdminService service;

    SlaConfigController(SlaAdminService service) {
        this.service = service;
    }

    @GetMapping("/schedules")
    @PreAuthorize(READ)
    CursorPage<SlaViews.Schedule> schedules(@RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return service.listSchedules(PageParams.of(cursor, limit));
    }

    @GetMapping("/schedules/{id}")
    @PreAuthorize(READ)
    ResponseEntity<SlaViews.Schedule> schedule(@PathVariable UUID id) {
        SlaViews.Schedule v = service.getSchedule(id);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping("/schedules")
    @PreAuthorize(WRITE)
    ResponseEntity<SlaViews.Schedule> createSchedule(@Valid @RequestBody SlaCommands.ScheduleCreate cmd) {
        SlaViews.Schedule v = service.createSchedule(cmd);
        return ResponseEntity.created(URI.create("/api/v1/sla/schedules/" + v.id())).eTag(ETags.of(v.version()))
                .body(v);
    }

    @PatchMapping("/schedules/{id}")
    @PreAuthorize(WRITE)
    ResponseEntity<SlaViews.Schedule> updateSchedule(@PathVariable UUID id,
                                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                     @Valid @RequestBody SlaCommands.ScheduleUpdate cmd) {
        SlaViews.Schedule v = service.updateSchedule(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/schedules/{id}/holidays")
    @PreAuthorize(READ)
    List<SlaViews.HolidayItem> holidays(@PathVariable UUID id, @RequestParam int year) {
        return service.holidays(id, checkYear(year));
    }

    @PutMapping("/schedules/{id}/holidays")
    @PreAuthorize(WRITE)
    List<SlaViews.HolidayItem> replaceHolidays(@PathVariable UUID id, @RequestParam int year,
                                               @Valid @RequestBody List<SlaCommands.HolidayInput> items) {
        return service.replaceHolidays(id, checkYear(year), items);
    }

    @GetMapping("/policies")
    @PreAuthorize(READ)
    CursorPage<SlaViews.Policy> policies(@RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return service.listPolicies(PageParams.of(cursor, limit));
    }

    @GetMapping("/policies/{id}")
    @PreAuthorize(READ)
    ResponseEntity<SlaViews.Policy> policy(@PathVariable UUID id) {
        SlaViews.Policy v = service.getPolicy(id);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping("/policies")
    @PreAuthorize(WRITE)
    ResponseEntity<SlaViews.Policy> createPolicy(@Valid @RequestBody SlaCommands.PolicyCreate cmd) {
        SlaViews.Policy v = service.createPolicy(cmd);
        return ResponseEntity.created(URI.create("/api/v1/sla/policies/" + v.id())).eTag(ETags.of(v.version()))
                .body(v);
    }

    @PatchMapping("/policies/{id}")
    @PreAuthorize(WRITE)
    ResponseEntity<SlaViews.Policy> updatePolicy(@PathVariable UUID id,
                                                 @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody SlaCommands.PolicyUpdate cmd) {
        SlaViews.Policy v = service.updatePolicy(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping("/policies/{id}/preview")
    @PreAuthorize(READ)
    SlaViews.Preview preview(@PathVariable UUID id, @Valid @RequestBody SlaCommands.PreviewInput in) {
        return service.preview(id, in);
    }

    /** Создаёт график и политику по умолчанию (раздел 5.3). Используется мастером и при ручной настройке. */
    @PostMapping("/setup/defaults")
    @PreAuthorize(WRITE)
    ResponseEntity<SlaViews.Policy> installDefaults(@RequestBody(required = false) Map<String, String> body) {
        String tz = body == null ? null : body.get("timezone");
        ZoneId zone;
        try {
            zone = ZoneId.of(tz == null ? "Europe/Moscow" : tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("invalid_timezone", "Неизвестный часовой пояс: " + tz);
        }
        SlaViews.Policy v = service.getPolicy(service.installDefaults(zone));
        return ResponseEntity.created(URI.create("/api/v1/sla/policies/" + v.id())).eTag(ETags.of(v.version()))
                .body(v);
    }

    private static int checkYear(int year) {
        if (year < 2000 || year > 2100) {
            throw ApiException.badRequest("invalid_year", "Год должен быть от 2000 до 2100");
        }
        return year;
    }
}
