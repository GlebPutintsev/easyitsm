package ru.easyitsm.admin.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Дежурства {@code /api/v1/admin/duty-shifts}: видят сотрудники поддержки, назначает ADM. */
@RestController
@RequestMapping("/api/v1/admin/duty-shifts")
class DutyController {

    private final DutyService service;

    DutyController(DutyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Roles.STAFF)
    List<DutyShift.View> list(@RequestParam UUID groupId, @RequestParam Instant from, @RequestParam Instant to) {
        return service.list(groupId, from, to);
    }

    @PostMapping
    @PreAuthorize(Roles.ADMIN)
    ResponseEntity<DutyShift.View> create(@Valid @RequestBody DutyService.Create cmd) {
        var v = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/admin/duty-shifts/" + v.id())).body(v);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
