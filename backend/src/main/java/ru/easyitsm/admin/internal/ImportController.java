package ru.easyitsm.admin.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.PageParams;

/**
 * Импорт CSV {@code /api/v1/admin/imports} (ADM-04): загрузить и посмотреть результат проверки, применить,
 * откатить. Только ADM.
 */
@RestController
@RequestMapping("/api/v1/admin/imports")
@PreAuthorize(Roles.ADMIN)
class ImportController {

    private final ImportService service;

    ImportController(ImportService service) {
        this.service = service;
    }

    /** Содержимое файла передаётся текстом (UTF-8): файл читает браузер, сервер не хранит его как вложение. */
    record Upload(@NotNull @Pattern(regexp = "users|groups|departments") String kind,
                  @Size(max = 255) String fileName,
                  @NotBlank String content) {
    }

    @GetMapping
    CursorPage<ImportService.JobSummary> list(@RequestParam(required = false) String cursor,
                                              @RequestParam(required = false) Integer limit) {
        return service.list(PageParams.of(cursor, limit));
    }

    @PostMapping
    ResponseEntity<ImportService.Job> upload(@Valid @RequestBody Upload upload) {
        var job = service.preview(upload.kind(), upload.fileName(), upload.content());
        return ResponseEntity.created(URI.create("/api/v1/admin/imports/" + job.id())).body(job);
    }

    @GetMapping("/{id}")
    ImportService.Job get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/apply")
    ImportService.Job apply(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean skipInvalid) {
        return service.apply(id, skipInvalid);
    }

    @PostMapping("/{id}/rollback")
    ImportService.Job rollback(@PathVariable UUID id) {
        return service.rollback(id);
    }
}
