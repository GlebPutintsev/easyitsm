package ru.easyitsm.admin.internal;

import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.admin.api.DictionaryItem;
import ru.easyitsm.platform.web.ETags;

/** Справочники «Управления» {@code /api/v1/admin/dictionaries}: читают сотрудники, меняет ADM. */
@RestController
@RequestMapping("/api/v1/admin/dictionaries")
class DictionaryController {

    private final DictionaryService service;

    DictionaryController(DictionaryService service) {
        this.service = service;
    }

    record SaveCommand(List<DictionaryItem> items, @Size(max = 1000) String comment) {
    }

    @GetMapping
    @PreAuthorize(Roles.STAFF)
    List<DictionaryService.Dictionary> all() {
        return service.all();
    }

    @GetMapping("/{kind}")
    @PreAuthorize(Roles.STAFF)
    ResponseEntity<DictionaryService.Dictionary> get(@PathVariable String kind) {
        var d = service.get(DictionaryService.kind(kind));
        return ResponseEntity.ok().eTag(ETags.of(d.version())).body(d);
    }

    @PutMapping("/{kind}")
    @PreAuthorize(Roles.ADMIN)
    ResponseEntity<DictionaryService.Dictionary> save(@PathVariable String kind,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                      @jakarta.validation.Valid @RequestBody SaveCommand cmd) {
        var d = service.save(DictionaryService.kind(kind), ifMatch, cmd.items(), cmd.comment());
        return ResponseEntity.ok().eTag(ETags.of(d.version())).body(d);
    }
}
