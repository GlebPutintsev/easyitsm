package ru.easyitsm.reporting.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.reporting.internal.ExportService.ExportFile;
import ru.easyitsm.reporting.internal.ReportViews.Dashboard;
import ru.easyitsm.reporting.internal.ReportViews.DashboardRef;
import ru.easyitsm.reporting.internal.ReportViews.ExportEntry;
import ru.easyitsm.reporting.internal.ReportViews.TicketRow;
import ru.easyitsm.reporting.internal.TicketDrill.Drill;

/**
 * Отчёты: готовые дашборды, провал в заявки и выгрузка (RPT-01, RPT-04, 10.15). Права — {@link ReportScope}.
 * Выгрузка требует недавнего подтверждения входа (SEC-08) и пишется в журнал.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private final Dashboards dashboards;
    private final TicketDrill drill;
    private final ExportService exports;
    private final ReportScope.Factory scopes;

    ReportController(Dashboards dashboards, TicketDrill drill, ExportService exports, ReportScope.Factory scopes) {
        this.dashboards = dashboards;
        this.drill = drill;
        this.exports = exports;
        this.scopes = scopes;
    }

    /** Фильтры дашборда и параметры провала: в запросе списка и в теле выгрузки одни и те же поля. */
    record Query(String from, String to, String tz, String groupId, String type, String priority, String serviceId,
                 String lang, String metric, String status, String channel, String date, String age, String score,
                 String format, String reason) {

        ReportFilter filter() {
            return ReportFilter.of(from, to, tz, groupId, type, priority, serviceId, lang);
        }

        Drill drill() {
            return Drill.of(metric, status, null, null, null, null, channel, date, age, score);
        }
    }

    @GetMapping("/dashboards")
    @PreAuthorize(ReportScope.READERS)
    List<DashboardRef> list(@RequestParam(required = false) String lang) {
        scopes.current();
        return dashboards.list("en".equals(lang) ? "en" : "ru");
    }

    @GetMapping("/dashboards/{id}")
    @PreAuthorize(ReportScope.READERS)
    Dashboard dashboard(@PathVariable String id, Query q) {
        ReportScope scope = scopes.current();
        ReportFilter filter = q.filter();
        scope.requireGroup(filter.groupUuid());
        return dashboards.build(id, filter, scope);
    }

    @GetMapping("/tickets")
    @PreAuthorize(ReportScope.READERS)
    CursorPage<TicketRow> tickets(Query q, @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        ReportScope scope = scopes.current();
        ReportFilter filter = q.filter();
        scope.requireGroup(filter.groupUuid());
        return drill.page(filter, q.drill(), scope, PageParams.of(cursor, limit));
    }

    @PostMapping("/dashboards/{id}/export")
    @PreAuthorize(ReportScope.READERS)
    @RequiresRecentAuth
    ResponseEntity<byte[]> exportDashboard(@PathVariable String id, @RequestBody Query q) {
        ReportScope scope = scopes.current();
        ReportFilter filter = q.filter();
        scope.requireGroup(filter.groupUuid());
        if (!Dashboards.IDS.contains(id)) {
            throw ru.easyitsm.platform.web.ApiException.notFound("Дашборд");
        }
        return file(exports.dashboard(id, filter, scope, q.format()));
    }

    @PostMapping("/tickets/export")
    @PreAuthorize(ReportScope.READERS)
    @RequiresRecentAuth
    ResponseEntity<byte[]> exportTickets(@RequestBody Query q) {
        ReportScope scope = scopes.current();
        ReportFilter filter = q.filter();
        scope.requireGroup(filter.groupUuid());
        return file(exports.tickets(filter, q.drill(), scope, q.format(), q.reason()));
    }

    /** Журнал выгрузок арендатора для администратора безопасности и аудитора (SEC-29). */
    @GetMapping("/exports")
    @PreAuthorize("hasAnyRole('SEC', 'AUD')")
    CursorPage<ExportEntry> journal(@RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        return exports.journal(PageParams.of(cursor, limit));
    }

    private static ResponseEntity<byte[]> file(ExportFile f) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(f.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.content());
    }
}
