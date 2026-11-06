package ru.easyitsm.audit.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.reporting.events.ReportExported;

/**
 * Выгрузки отчётов (RPT-04) в журнал аудита как {@code data.export} (SEC-14, SEC-29): кто, что, сколько строк,
 * с какими фильтрами и по какой причине. Журнал выгрузок модуля reporting остаётся его собственным, здесь —
 * запись в общей цепочке, которая уходит в SIEM.
 */
@Component
class ReportEventsListener {

    private final AuditWriter writer;

    ReportEventsListener(AuditWriter writer) {
        this.writer = writer;
    }

    @TransactionalEventListener
    void on(ReportExported e) {
        AuditEntry entry = AuditEntry.of(AuditCategory.DATA, "data.export")
                .actor(e.actorId(), null)
                .object("report_export", e.exportId())
                .detail("dataset", "report:" + e.report())
                .detail("format", e.format())
                .detail("rows", e.rowCount())
                .detail("reason", e.reason());
        if (e.filters() != null) {
            e.filters().forEach((k, v) -> entry.detail("filter." + k, v));
        }
        TenantContext.runAs(e.tenantId(), () -> writer.recordSeparately(entry));
    }
}
