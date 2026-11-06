package ru.easyitsm.admin.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
import ru.easyitsm.platform.web.ETags;

/** Организации и подразделения {@code /api/v1/admin/org-units}: читают сотрудники, меняет ADM. */
@RestController
@RequestMapping("/api/v1/admin/org-units")
class OrgUnitController {

    private final OrgUnitService service;

    OrgUnitController(OrgUnitService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Roles.STAFF)
    List<OrgUnit.View> list(@RequestParam(required = false) String kind, @RequestParam(required = false) String q) {
        return service.search(kind, q);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.STAFF)
    ResponseEntity<OrgUnit.View> get(@PathVariable UUID id) {
        var v = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping
    @PreAuthorize(Roles.ADMIN)
    ResponseEntity<OrgUnit.View> create(@Valid @RequestBody OrgUnitService.Create cmd) {
        var v = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/admin/org-units/" + v.id())).eTag(ETags.of(v.version()))
                .body(v);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    ResponseEntity<OrgUnit.View> update(@PathVariable UUID id,
                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                        @Valid @RequestBody OrgUnitService.Update cmd) {
        var v = service.update(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }
}
