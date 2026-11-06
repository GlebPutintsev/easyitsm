package ru.easyitsm.audit.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.audit.api.AuditResult;
import ru.easyitsm.audit.api.BulkExport;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.PageParams;

/**
 * «Управление → Журнал аудита» (10.16): SEC и AUD читают, фильтруют, проверяют целостность и выгружают
 * ({@code GET /audit/events}, раздел 8.2). Состояние SIEM и проверка связи — только SEC.
 */
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasAnyRole('SEC', 'AUD')")
class AuditController {

    private final AuditQueries queries;
    private final AuditChainVerifier verifier;
    private final AuditWriter writer;
    private final SiemForwarder siem;
    private final AuditProperties props;

    AuditController(AuditQueries queries, AuditChainVerifier verifier, AuditWriter writer, SiemForwarder siem,
                    AuditProperties props) {
        this.queries = queries;
        this.verifier = verifier;
        this.writer = writer;
        this.siem = siem;
        this.props = props;
    }

    @GetMapping("/events")
    @Transactional(readOnly = true)
    CursorPage<AuditViews.Event> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        var filter = new AuditViews.Filter(from, to, category, action, result, actorId, objectType, objectId, q);
        return queries.list(filter, PageParams.of(cursor, limit));
    }

    @GetMapping("/events/{id}")
    @Transactional(readOnly = true)
    AuditViews.Event get(@PathVariable UUID id) {
        return queries.get(id);
    }

    /** «Проверить целостность». Результат сам попадает в журнал. */
    @PostMapping("/verify")
    AuditViews.Verification verify() {
        AuditViews.Verification result = verifier.verify();
        writer.recordSeparately(verificationEntry(result).detail("trigger", "manual"));
        return result;
    }

    static AuditEntry verificationEntry(AuditViews.Verification v) {
        return AuditEntry.of(AuditCategory.AUDIT, "audit.verify")
                .result(v.valid() ? AuditResult.SUCCESS : AuditResult.FAILURE)
                .detail("checked", v.checked()).detail("headSeq", v.headSeq())
                .detail("brokenAtSeq", v.brokenAtSeq()).detail("problem", v.problem());
    }

    /**
     * Выгрузка в CSV (SEC-14): причина, повторный вход, запись в журнал до отправки файла. Права на выгрузку
     * у SEC и AUD — пересечение «Читать журнал» и «Массовая выгрузка» матрицы 3.2.
     */
    @PostMapping("/export")
    @PreAuthorize("hasAnyRole('SEC', 'AUD') and " + BulkExport.ROLES)
    @RequiresRecentAuth
    @Transactional
    void export(@RequestBody AuditViews.ExportRequest req, HttpServletResponse response) throws IOException {
        String reason = BulkExport.requireReason(req.reason());
        AuditViews.Filter filter = req.filter();
        // Число строк заранее неизвестно: в журнал пишутся причина, фильтр и предел; запись — до отдачи файла,
        // в одной транзакции с выборкой.
        Map<String, Object> describe = new LinkedHashMap<>(filter.describe());
        describe.put("limit", props.exportMax());
        writer.record(AuditEntry.of(AuditCategory.AUDIT, "audit.export").object("dataset", "audit")
                .detail("reason", reason).detail("filter", describe));
        String name = "audit-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
                .format(Instant.now()) + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name)
                .build().toString());
        Writer w = new BufferedWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));
        AuditCsv csv = new AuditCsv(w);
        csv.header();
        queries.stream(filter, props.exportMax(), csv::event);
        w.flush();
    }

    /** Состояние доставки в SIEM для экрана журнала. */
    @GetMapping("/siem")
    @PreAuthorize("hasRole('SEC')")
    @Transactional(readOnly = true)
    Map<String, Object> siemStatus() {
        UUID tenant = TenantContext.require();
        AuditProperties.Siem s = siem.settings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.configured());
        out.put("transport", s.transport());
        out.put("format", s.format());
        out.put("target", s.configured() ? s.host() + ":" + s.port() : null);
        long head = queries.headSeq();
        out.put("headSeq", head);
        queries.siemState(tenant).ifPresentOrElse(st -> {
            out.putAll(st);
            out.put("lag", Math.max(0, head - ((Number) st.get("deliveredSeq")).longValue()));
        }, () -> {
            out.put("deliveredSeq", 0L);
            out.put("lag", head);
        });
        return out;
    }

    /** «Проверить» связь с SIEM: тестовое сообщение сразу, мимо очереди. */
    @PostMapping("/siem/test")
    @PreAuthorize("hasRole('SEC')")
    Map<String, Object> siemTest() {
        if (!siem.settings().configured()) {
            throw ru.easyitsm.platform.web.ApiException.conflict("siem_disabled",
                    "Отправка в SIEM не настроена: easyitsm.audit.siem");
        }
        UUID tenant = TenantContext.require();
        var probe = new AuditViews.Event(Ids.newId(), 0, Instant.now(), "audit", "audit.siem.test", "success",
                null, null, null, null, null, null, null, Map.of("message", "Проверка связи Easy ITSM"),
                AuditChainVerifier.GENESIS, AuditChainVerifier.GENESIS);
        try {
            siem.sendTest(probe, siem.tenantCode(tenant));
            writer.recordSeparately(AuditEntry.of(AuditCategory.AUDIT, "audit.siem.test"));
            return Map.of("ok", true);
        } catch (Exception e) {
            String error = ru.easyitsm.audit.api.Masking.text(e.getClass().getSimpleName() + ": " + e.getMessage());
            writer.recordSeparately(AuditEntry.of(AuditCategory.AUDIT, "audit.siem.test")
                    .result(AuditResult.FAILURE).detail("error", error));
            return Map.of("ok", false, "error", error);
        }
    }
}
