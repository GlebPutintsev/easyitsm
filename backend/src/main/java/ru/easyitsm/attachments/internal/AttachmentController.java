package ru.easyitsm.attachments.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * Вложения. Загрузка в два шага: {@code POST /attachments} даёт подписанную ссылку, браузер кладёт файл в S3,
 * {@code POST /attachments/{id}/complete} отправляет его на проверку. Скачивание и просмотр — переадресация
 * 303 на короткую подписанную ссылку домена файлов.
 */
@RestController
@RequestMapping("/api/v1/attachments")
class AttachmentController {

    private final AttachmentService service;

    AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @GetMapping
    List<AttachmentViews.Attachment> list(@RequestParam String ownerType, @RequestParam UUID ownerId) {
        return service.list(ownerType, ownerId);
    }

    @PostMapping
    ResponseEntity<AttachmentViews.Created> create(@Valid @RequestBody AttachmentViews.NewUpload in) {
        AttachmentViews.Created c = service.create(in);
        return ResponseEntity.created(URI.create("/api/v1/attachments/" + c.attachment().id()))
                .eTag(ETags.of(c.attachment().version())).body(c);
    }

    @PostMapping("/{id}/complete")
    AttachmentViews.Attachment complete(@PathVariable UUID id) {
        return service.complete(id);
    }

    @GetMapping("/{id}")
    ResponseEntity<AttachmentViews.Attachment> get(@PathVariable UUID id) {
        AttachmentViews.Attachment a = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(a.version())).body(a);
    }

    @GetMapping("/{id}/download")
    ResponseEntity<Void> download(@PathVariable UUID id) {
        return redirect(service.downloadUrl(id, false));
    }

    @GetMapping("/{id}/preview")
    ResponseEntity<Void> preview(@PathVariable UUID id) {
        return redirect(service.downloadUrl(id, true));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/quarantine")
    @PreAuthorize("hasAnyRole('SEC', 'ADM')")
    CursorPage<AttachmentViews.Attachment> quarantine(@RequestParam(required = false) String cursor,
                                                      @RequestParam(required = false) Integer limit) {
        return service.quarantine(PageParams.of(cursor, limit));
    }

    @PostMapping("/{id}/rescan")
    @PreAuthorize("hasRole('SEC')")
    AttachmentViews.Attachment rescan(@PathVariable UUID id) {
        return service.rescan(id);
    }

    /** Ссылка живёт минуты и не должна оседать в кэшах и истории. */
    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, url)
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
    }
}
