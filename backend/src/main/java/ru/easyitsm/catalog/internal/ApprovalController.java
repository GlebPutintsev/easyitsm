package ru.easyitsm.catalog.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * Согласования (10.8, 8.2 ТЗ). Решение принимает только назначенный согласующий: право следует из
 * назначения (ABAC), а не из роли. Шаблоны маршрутов настраивает ADM.
 */
@RestController
@RequestMapping("/api/v1")
class ApprovalController {

    private final ApprovalService service;

    ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /** @param tab pending — ждут меня, decided — мои решения */
    @GetMapping("/approvals")
    CursorPage<CatalogViews.ApprovalView> mine(@RequestParam(defaultValue = "pending") String tab,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        if (!tab.equals("pending") && !tab.equals("decided")) {
            throw ApiException.badRequest("invalid_tab", "tab: pending или decided");
        }
        return service.mine(tab, PageParams.of(cursor, limit));
    }

    @GetMapping("/approvals/count")
    Map<String, Long> count() {
        return Map.of("pending", service.pendingCount(Roles.actor()));
    }

    @PostMapping("/approvals/{id}/decision")
    CatalogViews.ApprovalView decide(@PathVariable UUID id, @Valid @RequestBody CatalogCommands.Decision cmd) {
        return service.decide(id, cmd);
    }

    @PostMapping("/approvals/{id}/delegate")
    CatalogViews.ApprovalView delegate(@PathVariable UUID id, @Valid @RequestBody CatalogCommands.Delegate cmd) {
        return service.delegate(id, cmd);
    }

    /** Маршрут согласования заявки для карточки на портале и у агента. */
    @GetMapping("/approvals/routes/{ticketId}")
    CatalogViews.RouteView route(@PathVariable UUID ticketId) {
        return service.route(ticketId);
    }

    // ---------- шаблоны маршрутов ----------

    @GetMapping("/catalog/approval-flows")
    @PreAuthorize("hasRole('ADM')")
    CursorPage<CatalogViews.FlowView> flows() {
        List<CatalogViews.FlowView> all = service.flows();
        return new CursorPage<>(all, null);
    }

    @GetMapping("/catalog/approval-flows/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CatalogViews.FlowView> flow(@PathVariable UUID id) {
        var view = service.flow(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping("/catalog/approval-flows")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CatalogViews.FlowView> createFlow(@Valid @RequestBody CatalogCommands.FlowInput in) {
        var view = service.createFlow(in);
        return ResponseEntity.created(URI.create("/api/v1/catalog/approval-flows/" + view.id()))
                .eTag(ETags.of(view.version())).body(view);
    }

    @PutMapping("/catalog/approval-flows/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<CatalogViews.FlowView> updateFlow(
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody CatalogCommands.FlowInput in) {
        var view = service.updateFlow(id, ifMatch, in);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}
