package ru.easyitsm.tickets.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.tickets.api.TicketType;

/** Настройки заявок {@code /api/v1/ticket-settings/**}: читают поддержка и администратор, меняет ADM. */
@RestController
@RequestMapping("/api/v1/ticket-settings")
@PreAuthorize("hasAnyRole('ADM', 'AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST', 'AUD')")
class TicketSettingsController {

    private final TicketSettings settings;
    private final CustomFields fields;
    private final Routing routing;

    TicketSettingsController(TicketSettings settings, CustomFields fields, Routing routing) {
        this.settings = settings;
        this.fields = fields;
        this.routing = routing;
    }

    @GetMapping("/workflows")
    List<Workflow> workflows() {
        return settings.workflows();
    }

    @GetMapping("/workflows/{type}")
    ResponseEntity<Workflow> workflow(@PathVariable String type) {
        Workflow wf = settings.workflow(TicketType.fromCode(type));
        return ResponseEntity.ok().eTag(ETags.of(wf.version())).body(wf);
    }

    @PutMapping("/workflows/{type}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<Workflow> saveWorkflow(@PathVariable String type,
                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                          @RequestBody Workflow wf) {
        Workflow saved = settings.saveWorkflow(TicketType.fromCode(type), ifMatch, wf);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(saved);
    }

    @GetMapping("/priority-matrix")
    ResponseEntity<PriorityMatrix> matrix() {
        PriorityMatrix m = settings.matrix();
        return ResponseEntity.ok().eTag(ETags.of(m.version())).body(m);
    }

    @PutMapping("/priority-matrix")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<PriorityMatrix> saveMatrix(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @RequestBody PriorityMatrix m) {
        PriorityMatrix saved = settings.saveMatrix(ifMatch, m);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(saved);
    }

    @GetMapping("/general")
    ResponseEntity<GeneralSettings> general() {
        GeneralSettings g = settings.general();
        return ResponseEntity.ok().eTag(ETags.of(g.version())).body(g);
    }

    @PutMapping("/general")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<GeneralSettings> saveGeneral(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @RequestBody GeneralSettings g) {
        GeneralSettings saved = settings.saveGeneral(ifMatch, g);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(saved);
    }

    @GetMapping("/fields")
    List<CustomFields.Field> fields(@RequestParam(required = false) String type) {
        return fields.list(type == null ? null : TicketType.fromCode(type));
    }

    @PostMapping("/fields")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CustomFields.Field> createField(@RequestBody CustomFields.Field f) {
        CustomFields.Field saved = fields.create(f);
        return ResponseEntity.created(URI.create("/api/v1/ticket-settings/fields/" + saved.id()))
                .eTag(ETags.of(saved.version())).body(saved);
    }

    @PatchMapping("/fields/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CustomFields.Field> updateField(@PathVariable UUID id,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                   @RequestBody CustomFields.Field f) {
        CustomFields.Field saved = fields.update(id, ifMatch, f);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(saved);
    }

    @GetMapping("/categories")
    List<TicketSettings.Category> categories() {
        return settings.categories();
    }

    @PostMapping("/categories")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<TicketSettings.Category> createCategory(@RequestBody TicketSettings.Category c) {
        TicketSettings.Category saved = settings.createCategory(c);
        return ResponseEntity.created(URI.create("/api/v1/ticket-settings/categories/" + saved.id()))
                .eTag(ETags.of(saved.version())).body(saved);
    }

    @PatchMapping("/categories/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<TicketSettings.Category> updateCategory(@PathVariable UUID id,
                                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                           @RequestBody TicketSettings.Category c) {
        TicketSettings.Category saved = settings.updateCategory(id, ifMatch, c);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(saved);
    }

    @GetMapping("/routing-rules")
    List<Routing.Rule> rules() {
        return routing.rules();
    }

    @PostMapping("/routing-rules")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<Routing.Rule> createRule(@RequestBody Routing.Rule r) {
        Routing.Rule saved = routing.createRule(r);
        return ResponseEntity.created(URI.create("/api/v1/ticket-settings/routing-rules/" + saved.id()))
                .eTag(ETags.of(saved.version())).body(saved);
    }

    @PatchMapping("/routing-rules/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<Routing.Rule> updateRule(@PathVariable UUID id,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            @RequestBody Routing.Rule r) {
        Routing.Rule saved = routing.updateRule(id, ifMatch, r);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(saved);
    }

    @DeleteMapping("/routing-rules/{id}")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> deleteRule(@PathVariable UUID id) {
        routing.deleteRule(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/group-dispatch/{groupId}")
    Routing.Dispatch dispatch(@PathVariable UUID groupId) {
        return routing.dispatchSettings(groupId);
    }

    @PutMapping("/group-dispatch/{groupId}")
    @PreAuthorize("hasAnyRole('ADM', 'LEAD')")
    Routing.Dispatch saveDispatch(@PathVariable UUID groupId, @RequestBody Routing.Dispatch d) {
        return routing.saveDispatch(groupId, d);
    }
}
