package ru.easyitsm.assets.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.assets.internal.AssetDtos.AssetView;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * Оборудование (AST-01, AST-05, раздел 10.14 ТЗ): список, карточка, история и действия «Выдать», «Вернуть»,
 * «Передать», «В ремонт», «Списать». Каждое действие — с {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1/assets")
class AssetController {

    private final AssetService service;
    private final SoftwareLicenseService licenses;

    AssetController(AssetService service, SoftwareLicenseService licenses) {
        this.service = service;
        this.licenses = licenses;
    }

    @GetMapping
    @PreAuthorize(AssetRoles.READ)
    CursorPage<AssetDtos.AssetRow> list(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) @Pattern(regexp = AssetDtos.STATES) String state,
                                        @RequestParam(required = false) @Pattern(regexp = AssetDtos.KINDS) String kind,
                                        @RequestParam(required = false) UUID holderId,
                                        @RequestParam(required = false) UUID locationId,
                                        @RequestParam(required = false) UUID ciId,
                                        @RequestParam(required = false) UUID contractId,
                                        @RequestParam(required = false) UUID vendorId,
                                        @RequestParam(required = false) UUID ticketId,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return service.list(new AssetSearch.Filter(q, state, kind, holderId, locationId, ciId, contractId, vendorId,
                ticketId), PageParams.of(cursor, limit));
    }

    @GetMapping("/stats")
    @PreAuthorize(AssetRoles.READ)
    AssetDtos.AssetStats stats() {
        return service.stats();
    }

    @GetMapping("/{id}")
    @PreAuthorize(AssetRoles.READ)
    ResponseEntity<AssetView> get(@PathVariable UUID id) {
        return ok(service.get(id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(AssetRoles.READ)
    List<AssetDtos.AssetEventView> history(@PathVariable UUID id) {
        return service.history(id);
    }

    @GetMapping("/{id}/licenses")
    @PreAuthorize(AssetRoles.READ)
    List<AssetDtos.AssignmentView> licenses(@PathVariable UUID id) {
        return licenses.ofAsset(id);
    }

    @PostMapping
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetView> create(@Valid @RequestBody AssetDtos.AssetFields cmd) {
        AssetView view = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/assets/" + view.id())).eTag(ETags.of(view.version()))
                .body(view);
    }

    @PatchMapping("/{id}")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetView> update(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                     @Valid @RequestBody AssetDtos.AssetFields cmd) {
        return ok(service.update(id, ifMatch, cmd));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetView> issue(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                    @Valid @RequestBody AssetDtos.Assign cmd) {
        return ok(service.issue(id, ifMatch, cmd));
    }

    @PostMapping("/{id}/transfer")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetView> transfer(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       @Valid @RequestBody AssetDtos.Assign cmd) {
        return ok(service.transfer(id, ifMatch, cmd));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetView> giveBack(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                       @Valid @RequestBody AssetDtos.Move cmd) {
        return ok(service.giveBack(id, ifMatch, cmd));
    }

    @PostMapping("/{id}/repair")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetView> repair(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                     @Valid @RequestBody AssetDtos.Move cmd) {
        return ok(service.repair(id, ifMatch, cmd));
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize(AssetRoles.MANAGE)
    ResponseEntity<AssetView> retire(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                     @Valid @RequestBody AssetDtos.Retire cmd) {
        return ok(service.retire(id, ifMatch, cmd));
    }

    @GetMapping("/{id}/tickets")
    @PreAuthorize(AssetRoles.READ)
    List<AssetDtos.TicketInfo> tickets(@PathVariable UUID id) {
        return service.tickets(id);
    }

    @PostMapping("/{id}/tickets")
    @PreAuthorize(AssetRoles.LINK)
    List<AssetDtos.TicketInfo> linkTicket(@PathVariable UUID id, @Valid @RequestBody AssetDtos.LinkTicket cmd) {
        return service.linkTicket(id, cmd);
    }

    @DeleteMapping("/{id}/tickets/{ticketId}")
    @PreAuthorize(AssetRoles.LINK)
    ResponseEntity<Void> unlinkTicket(@PathVariable UUID id, @PathVariable UUID ticketId) {
        service.unlinkTicket(id, ticketId);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<AssetView> ok(AssetView view) {
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}
