package ru.easyitsm.audit.api;

import ru.easyitsm.platform.web.ApiException;

/**
 * Правила массовой выгрузки (SEC-14, матрица 3.2 «Массовая выгрузка»): отдельное право, причина, повторная
 * аутентификация и запись в журнал. Эндпоинт выгрузки оформляется так:
 * <pre>{@code
 * @PostMapping("/export")
 * @PreAuthorize(BulkExport.ROLES)
 * @RequiresRecentAuth
 * ResponseEntity<byte[]> export(@RequestBody ExportRequest req) {
 *     String reason = BulkExport.requireReason(req.reason());
 *     ... выборка с проверкой прав на каждую запись ...
 *     auditLog.exported("tickets", reason, rows.size(), filter);
 * }
 * }</pre>
 */
public final class BulkExport {

    /** Роли с правом массовой выгрузки: LEAD, CFG, ADM, SEC, AUD («С» в матрице 3.2). */
    public static final String ROLES = "hasAnyRole('LEAD', 'CFG', 'ADM', 'SEC', 'AUD')";

    public static final int MIN_REASON = 5;
    public static final int MAX_REASON = 500;

    private BulkExport() {
    }

    /** Причина обязательна: от {@value #MIN_REASON} до {@value #MAX_REASON} символов, секреты маскируются. */
    public static String requireReason(String reason) {
        String r = reason == null ? "" : reason.strip();
        if (r.length() < MIN_REASON || r.length() > MAX_REASON) {
            throw ApiException.badRequest("export_reason_required",
                    "Укажите причину выгрузки: от " + MIN_REASON + " до " + MAX_REASON + " символов");
        }
        return Masking.text(r);
    }
}
