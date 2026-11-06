package ru.easyitsm.assets.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.assets.internal.AssetDtos.LicenseView;
import ru.easyitsm.platform.web.ETags;

/** Лицензии ПО (AST-03): права, использование и сроки. */
@RestController
@RequestMapping("/api/v1/licenses")
class SoftwareLicenseController {

    private final SoftwareLicenseService service;

    SoftwareLicenseController(SoftwareLicenseService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(AssetRoles.READ)
    List<LicenseView> list(@RequestParam(required = false) String q,
                           @RequestParam(required = false) @Pattern(regexp = "active|expiring|expired|overused") String status) {
        return service.list(q, status);
    }

    @GetMapping("/{id}")
    @PreAuthorize(AssetRoles.READ)
    ResponseEntity<LicenseView> get(@PathVariable UUID id) {
        LicenseView v = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<LicenseView> create(@Valid @RequestBody AssetDtos.LicenseFields cmd) {
        LicenseView v = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/licenses/" + v.id())).eTag(ETags.of(v.version())).body(v);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<LicenseView> update(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       @Valid @RequestBody AssetDtos.LicenseFields cmd) {
        LicenseView v = service.update(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/{id}/assignments")
    @PreAuthorize(AssetRoles.READ)
    List<AssetDtos.AssignmentView> assignments(@PathVariable UUID id) {
        return service.assignments(id);
    }

    @PostMapping("/{id}/assignments")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetDtos.AssignmentView> assign(@PathVariable UUID id, @Valid @RequestBody AssetDtos.Assignment cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.assign(id, cmd));
    }

    @DeleteMapping("/{id}/assignments/{assignmentId}")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<Void> unassign(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        service.unassign(id, assignmentId);
        return ResponseEntity.noContent().build();
    }
}
