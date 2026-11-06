package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * «Управление → Интеграции → Мониторинг»: источники, токены, активные алерты и журнал событий.
 * Изменять может ADM, читать — ещё AUD.
 */
@RestController
@RequestMapping("/api/v1/integrations/monitoring")
@PreAuthorize("hasAnyRole('ADM', 'AUD')")
class MonitoringController {

    /** Активный алерт для экрана: важность — код в нижнем регистре, как в настройках источника. */
    record AlertView(UUID id, String fingerprint, UUID ticketId, String ticketNumber, String severity, String title,
                     int occurrences, Instant firstAt, Instant lastAt) {

        static AlertView of(AlertStore.Row r) {
            return new AlertView(r.id(), r.fingerprint(), r.ticketId(), r.ticketNumber(), r.severity().code(),
                    r.title(), r.occurrences(), r.firstAt(), r.lastAt());
        }
    }

    private final MonitoringService service;

    MonitoringController(MonitoringService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    CursorPage<MonitoringService.SourceView> sources() {
        return new CursorPage<>(service.list(), null);
    }

    @GetMapping("/sources/{id}")
    ResponseEntity<MonitoringService.SourceView> source(@PathVariable UUID id) {
        MonitoringService.SourceView s = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(s.source().version())).body(s);
    }

    @PostMapping("/sources")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<MonitoringService.WithToken> create(@RequestBody SourceInput input) {
        MonitoringService.WithToken created = service.create(input);
        MonitoringSource s = created.source().source();
        return ResponseEntity.created(URI.create("/api/v1/integrations/monitoring/sources/" + s.id()))
                .eTag(ETags.of(s.version())).body(created);
    }

    @PatchMapping("/sources/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<MonitoringService.SourceView> update(@PathVariable UUID id,
                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                        String ifMatch,
                                                        @RequestBody SourceInput input) {
        MonitoringService.SourceView s = service.update(id, ifMatch, input);
        return ResponseEntity.ok().eTag(ETags.of(s.source().version())).body(s);
    }

    /** Перевыпуск токена: прежний перестаёт приниматься сразу. */
    @PostMapping("/sources/{id}/token")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    MonitoringService.WithToken rotate(@PathVariable UUID id) {
        return service.rotate(id);
    }

    @DeleteMapping("/sources/{id}")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sources/{id}/alerts")
    CursorPage<AlertView> alerts(@PathVariable UUID id) {
        List<AlertView> rows = service.activeAlerts(id).stream().map(AlertView::of).toList();
        return new CursorPage<>(rows, null);
    }

    @GetMapping("/events")
    CursorPage<EventLog.Entry> events(@RequestParam(required = false) UUID sourceId,
                                      @RequestParam(required = false) String outcome,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return service.events(sourceId, outcome, PageParams.of(cursor, limit));
    }
}
