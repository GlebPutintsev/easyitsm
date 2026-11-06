package ru.easyitsm.changes.internal;

import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
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
import ru.easyitsm.audit.api.AuditCategory;
import ru.easyitsm.audit.api.AuditEntry;
import ru.easyitsm.audit.api.AuditLog;

/**
 * Выгрузка плана работ по форме ЗНИ (CHG-09): XLSX, DOCX, PDF. Документ одной записи для согласования вне системы,
 * не массовая выгрузка, поэтому без причины и повторного входа; факт выгрузки пишется в журнал аудита.
 */
@RestController
class PlanExportController {

    private final ChangeService service;
    private final PlanDocument documents;
    private final AuditLog audit;

    PlanExportController(ChangeService service, PlanDocument documents, AuditLog audit) {
        this.service = service;
        this.documents = documents;
        this.audit = audit;
    }

    @GetMapping("/api/v1/changes/{id}/plan-document")
    @PreAuthorize(ChangeController.READERS)
    @Transactional
    ResponseEntity<byte[]> document(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "xlsx") @Pattern(regexp = "xlsx|docx|pdf") String format) {
        ChangeRow c = service.readable(id, Me.current());
        PlanDocument.Doc doc = documents.build(c);
        byte[] body = switch (format) {
            case "docx" -> PlanDocx.write(doc);
            case "pdf" -> PlanPdf.write(doc);
            default -> PlanXlsx.write(doc);
        };
        MediaType type = switch (format) {
            case "docx" -> MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "pdf" -> MediaType.APPLICATION_PDF;
            default -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        };
        audit.record(AuditEntry.of(AuditCategory.DATA, "changes.plan.export")
                .object("change", c.id)
                .detail("number", c.number)
                .detail("format", format));
        String name = PlanDocument.safeFileName(doc.fileName()) + "." + format;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
