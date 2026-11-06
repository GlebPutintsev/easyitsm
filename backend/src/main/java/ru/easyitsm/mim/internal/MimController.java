package ru.easyitsm.mim.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.mim.internal.MimDtos.MajorIncidentRow;
import ru.easyitsm.mim.internal.MimDtos.MajorIncidentView;
import ru.easyitsm.mim.internal.MimDtos.Rendered;
import ru.easyitsm.mim.internal.MimDtos.StatusPage;
import ru.easyitsm.platform.web.ETags;

/**
 * REST командного центра крупных инцидентов (MIM-01…05). Права уровня ролей — здесь, права на конкретный
 * крупный инцидент (участник, видимость заявки) — в {@link MimService}. ETag — версия крупного инцидента.
 */
@RestController
@RequestMapping("/api/v1")
class MimController {

    static final String SUPPORT = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST')";
    static final String READERS = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST', 'AUD')";
    static final String DECLARERS = "hasAnyRole('LEAD', 'MIM')";

    private final MimService service;

    MimController(MimService service) {
        this.service = service;
    }

    @PostMapping("/major-incidents")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> declare(@Valid @RequestBody MimDtos.Declare in) {
        MajorIncidentView v = service.declare(in);
        return ResponseEntity.created(URI.create("/api/v1/major-incidents/" + v.id()))
                .eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/major-incidents")
    @PreAuthorize(READERS)
    List<MajorIncidentRow> list(@RequestParam(defaultValue = "open") String state,
                                @RequestParam(defaultValue = "100") int limit) {
        return service.list(state, limit);
    }

    @GetMapping("/major-incidents/by-ticket/{ticketId}")
    @PreAuthorize(READERS)
    ResponseEntity<MajorIncidentView> byTicket(@PathVariable UUID ticketId) {
        return ok(service.byTicket(ticketId));
    }

    @GetMapping("/major-incidents/{id}")
    @PreAuthorize(READERS)
    ResponseEntity<MajorIncidentView> get(@PathVariable UUID id) {
        return ok(service.get(id));
    }

    @PostMapping("/major-incidents/{id}/approve")
    @PreAuthorize(DECLARERS)
    ResponseEntity<MajorIncidentView> approve(@PathVariable UUID id) {
        return ok(service.approve(id));
    }

    @PostMapping("/major-incidents/{id}/reject")
    @PreAuthorize(DECLARERS)
    ResponseEntity<MajorIncidentView> reject(@PathVariable UUID id, @Valid @RequestBody MimDtos.Reject in) {
        return ok(service.reject(id, in));
    }

    @PatchMapping("/major-incidents/{id}")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> patch(@PathVariable UUID id,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                            String ifMatch,
                                            @Valid @RequestBody MimDtos.Patch in) {
        return ok(service.patch(id, in, ifMatch));
    }

    @PutMapping("/major-incidents/{id}/roles/{role}")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> role(@PathVariable UUID id, @PathVariable String role,
                                           @RequestBody MimDtos.AssignRole in) {
        return ok(service.assignRole(id, role, in.userId()));
    }

    @PostMapping("/major-incidents/{id}/events")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> event(@PathVariable UUID id, @Valid @RequestBody MimDtos.AddEvent in) {
        return ok(service.addEvent(id, in));
    }

    @PostMapping("/major-incidents/{id}/tasks")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> task(@PathVariable UUID id, @Valid @RequestBody MimDtos.AddTask in) {
        return ok(service.addTask(id, in));
    }

    @PutMapping("/major-incidents/{id}/tasks/{taskId}/done")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> taskDone(@PathVariable UUID id, @PathVariable UUID taskId,
                                               @RequestBody MimDtos.SetTaskDone in) {
        return ok(service.setTaskDone(id, taskId, in.done()));
    }

    @PostMapping("/major-incidents/{id}/children")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> children(@PathVariable UUID id,
                                               @Valid @RequestBody MimDtos.LinkChildren in) {
        return ok(service.linkChildren(id, in));
    }

    @PostMapping("/major-incidents/{id}/render")
    @PreAuthorize(READERS)
    Rendered render(@PathVariable UUID id, @Valid @RequestBody MimDtos.Render in) {
        return service.render(id, in.templateId());
    }

    @PostMapping("/major-incidents/{id}/updates")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> publish(@PathVariable UUID id, @Valid @RequestBody MimDtos.Publish in) {
        return ok(service.publish(id, in));
    }

    @PostMapping("/major-incidents/{id}/pages")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> page(@PathVariable UUID id, @Valid @RequestBody MimDtos.StartPage in) {
        return ok(service.startPage(id, in));
    }

    @PostMapping("/major-incidents/{id}/pages/{pageId}/cancel")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> cancelPage(@PathVariable UUID id, @PathVariable UUID pageId) {
        return ok(service.cancelPage(id, pageId));
    }

    /** Подтверждение получения оповещения: любой вошедший, кого оповестили. */
    @PostMapping("/major-incidents/pages/{pageId}/ack")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<MajorIncidentView> ack(@PathVariable UUID pageId) {
        return ok(service.acknowledge(pageId));
    }

    @PostMapping("/major-incidents/{id}/restore")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> restore(@PathVariable UUID id, @Valid @RequestBody MimDtos.Restore in) {
        return ok(service.restore(id, in));
    }

    @PostMapping("/major-incidents/{id}/close")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> close(@PathVariable UUID id, @Valid @RequestBody MimDtos.Close in) {
        return ok(service.close(id, in));
    }

    @GetMapping("/major-incidents/{id}/pir-draft")
    @PreAuthorize(READERS)
    MimDtos.SavePir pirDraft(@PathVariable UUID id) {
        return new MimDtos.SavePir(service.pirPreview(id));
    }

    @PutMapping("/major-incidents/{id}/pir")
    @PreAuthorize(SUPPORT)
    ResponseEntity<MajorIncidentView> pir(@PathVariable UUID id,
                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                          String ifMatch,
                                          @Valid @RequestBody MimDtos.SavePir in) {
        return ok(service.savePir(id, in.pir(), ifMatch));
    }

    /** Страница статуса (10.2): всем вошедшим, только публичные названия и сообщения пользователям. */
    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    StatusPage status() {
        return service.status();
    }

    private static ResponseEntity<MajorIncidentView> ok(MajorIncidentView v) {
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }
}
