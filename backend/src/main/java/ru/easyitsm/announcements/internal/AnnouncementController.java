package ru.easyitsm.announcements.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.announcements.api.AnnouncementView;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

@RestController
@RequestMapping("/api/v1/announcements")
class AnnouncementController {

    private final AnnouncementService service;

    AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    CursorPage<AnnouncementView> list(@RequestParam(defaultValue = "false") boolean active,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return service.list(active, PageParams.of(cursor, limit));
    }

    @GetMapping("/{id}")
    ResponseEntity<AnnouncementView> get(@PathVariable UUID id) {
        AnnouncementView view = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADM', 'MIM')")
    ResponseEntity<AnnouncementView> create(@Valid @RequestBody AnnouncementCommands.Create cmd) {
        AnnouncementView view = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/announcements/" + view.id()))
                .eTag(ETags.of(view.version()))
                .body(view);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADM', 'MIM')")
    ResponseEntity<AnnouncementView> update(@PathVariable UUID id,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @Valid @RequestBody AnnouncementCommands.Update cmd) {
        AnnouncementView view = service.update(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}
