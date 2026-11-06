package ru.easyitsm.reporting.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.reporting.events.ReportExported;
import ru.easyitsm.reporting.internal.NameBook.Names;
import ru.easyitsm.reporting.internal.ReportViews.Dashboard;
import ru.easyitsm.reporting.internal.ReportViews.ExportEntry;
import ru.easyitsm.reporting.internal.ReportViews.TicketRow;
import ru.easyitsm.reporting.internal.TicketDrill.Drill;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Выгрузка отчётов (RPT-04): файл CSV, XLSX или PDF, запись в журнал выгрузок и событие {@link ReportExported}
 * для модуля аудита — в одной транзакции. Если журнал не записался, файл не отдаётся.
 * <p>
 * Список заявок больше {@code easyitsm.reporting.export.reason-after} строк выгружается только с причиной
 * (10.6: «после превышения лимита требует причину»), больше {@code max-rows} — не выгружается: нужен фильтр уже.
 */
@Service
class ExportService {

    static final Set<String> FORMATS = Set.of("csv", "xlsx", "pdf");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    record ExportFile(byte[] content, String filename, String contentType) {
    }

    private final Dashboards dashboards;
    private final TicketDrill drill;
    private final NameBook names;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final int reasonAfter;
    private final int maxRows;
    private final int maxPdfRows;

    ExportService(Dashboards dashboards, TicketDrill drill, NameBook names, JdbcClient jdbc, JsonMapper json,
                  ApplicationEventPublisher events,
                  @Value("${easyitsm.reporting.export.reason-after:1000}") int reasonAfter,
                  @Value("${easyitsm.reporting.export.max-rows:50000}") int maxRows,
                  @Value("${easyitsm.reporting.export.max-pdf-rows:5000}") int maxPdfRows) {
        this.dashboards = dashboards;
        this.drill = drill;
        this.names = names;
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.reasonAfter = reasonAfter;
        this.maxRows = maxRows;
        this.maxPdfRows = maxPdfRows;
    }

    @Transactional
    ExportFile dashboard(String id, ReportFilter filter, ReportScope scope, String format) {
        String fmt = format(format);
        Dashboard d = dashboards.build(id, filter, scope);
        ExportModel model = ExportModel.of(d, filter, describe(filter));
        byte[] content = render(model, fmt);
        log("dashboard:" + id, fmt, filter.describe(), model.rowCount(), null, scope);
        return new ExportFile(content, filename(id, filter, fmt), contentType(fmt));
    }

    @Transactional
    ExportFile tickets(ReportFilter filter, Drill d, ReportScope scope, String format, String reason) {
        String fmt = format(format);
        long total = drill.count(filter, d, scope);
        int limit = "pdf".equals(fmt) ? Math.min(maxPdfRows, maxRows) : maxRows;
        if (total > limit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "export_too_large",
                    "Слишком много строк для выгрузки (" + total + ", не больше " + limit + "). Сузьте фильтр");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        if (total > reasonAfter && why == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "export_reason_required",
                    "Выгрузка больше " + reasonAfter + " строк требует причины");
        }
        if (why != null && why.length() > 500) {
            throw ApiException.badRequest("invalid_reason", "Причина не длиннее 500 символов");
        }
        List<TicketRow> rows = drill.all(filter, d, scope, limit);
        ExportModel model = ExportModel.tickets(rows, TicketDrill.narrow(filter, d), scope.drillAll(), describe(filter));
        byte[] content = render(model, fmt);
        Map<String, String> filters = new LinkedHashMap<>(filter.describe());
        filters.putAll(d.describe());
        log("tickets", fmt, filters, rows.size(), why, scope);
        return new ExportFile(content, filename("tickets", filter, fmt), contentType(fmt));
    }

    /** Журнал выгрузок арендатора (SEC, AUD), новые сверху. */
    @Transactional(readOnly = true)
    CursorPage<ExportEntry> journal(PageParams page) {
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                select id, report, format, filters::text as filters, row_count, reason, created_by, created_at
                from reporting_export e where true""");
        KeysetCursor cursor = page.keyset();
        if (cursor != null) {
            sql.append(" and (e.created_at, e.id) < (:at, :id)");
            try {
                p.put("at", Timestamp.from(Instant.parse(cursor.sortValue())));
            } catch (java.time.DateTimeException e) {
                throw ApiException.badRequest("invalid_cursor", "Некорректный курсор");
            }
            p.put("id", cursor.id());
        }
        sql.append(" order by e.created_at desc, e.id desc limit :limit");
        p.put("limit", page.limit() + 1);
        List<ExportEntry> rows = jdbc.sql(sql.toString()).params(p).query((rs, i) -> new ExportEntry(
                rs.getObject("id", UUID.class), rs.getString("report"), rs.getString("format"),
                json.readValue(rs.getString("filters"), STRING_MAP), rs.getInt("row_count"), rs.getString("reason"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant())).list();
        return CursorPage.of(rows, page.limit(), e -> new KeysetCursor(e.createdAt().toString(), e.id()));
    }

    private void log(String report, String format, Map<String, String> filters, int rows, String reason,
                     ReportScope scope) {
        UUID id = Ids.newId();
        UUID tenant = TenantContext.require();
        Instant now = Instant.now();
        jdbc.sql("""
                insert into reporting_export (id, tenant_id, report, format, filters, row_count, reason, created_at,
                    created_by)
                values (:id, :tenant, :report, :format, cast(:filters as jsonb), :rows, :reason, :at, :by)""")
                .param("id", id)
                .param("tenant", tenant)
                .param("report", report)
                .param("format", format)
                .param("filters", json.writeValueAsString(filters))
                .param("rows", rows)
                .param("reason", reason)
                .param("at", Timestamp.from(now))
                .param("by", scope.userId())
                .update();
        events.publishEvent(new ReportExported(tenant, now, scope.userId(), id, report, format, Map.copyOf(filters),
                rows, reason));
    }

    /** Фильтры словами для шапки файла: имена группы и услуги, тип и приоритет. */
    private String describe(ReportFilter f) {
        Names n = names.load(f.lang());
        List<String> parts = new ArrayList<>();
        if (f.groupId() != null) {
            parts.add(Labels.get(f.lang(), "col.group") + " — " + n.group(f.groupUuid()));
        }
        if (f.serviceId() != null) {
            UUID s = ReportFilter.NONE.equals(f.serviceId()) ? null : UUID.fromString(f.serviceId());
            parts.add(Labels.get(f.lang(), "col.service") + " — " + n.service(s));
        }
        if (f.type() != null) {
            parts.add(Labels.get(f.lang(), "col.type") + " — " + Labels.get(f.lang(), "type." + f.type()));
        }
        if (f.priority() != null) {
            parts.add(Labels.get(f.lang(), "col.priority") + " — " + Labels.get(f.lang(), "priority." + f.priority()));
        }
        return String.join("; ", parts);
    }

    private static byte[] render(ExportModel model, String format) {
        return switch (format) {
            case "csv" -> CsvWriter.write(model);
            case "xlsx" -> XlsxWriter.write(model);
            default -> PdfWriter.write(model);
        };
    }

    private static String format(String format) {
        if (format == null || !FORMATS.contains(format)) {
            throw ApiException.badRequest("invalid_format", "Формат: csv, xlsx или pdf");
        }
        return format;
    }

    private static String filename(String report, ReportFilter f, String format) {
        return "easyitsm-" + report + "-" + f.from() + "_" + f.to() + "." + format;
    }

    private static String contentType(String format) {
        return switch (format) {
            case "csv" -> "text/csv; charset=UTF-8";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/pdf";
        };
    }
}
