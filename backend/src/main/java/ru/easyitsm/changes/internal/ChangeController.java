package ru.easyitsm.changes.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * REST изменений (10.10). Читают поддержка, аудитор и согласующие; ведут — поддержка (права на запись проверяет
 * {@link ChangeAccess}); на согласование отправляет и закрывает менеджер изменений (CHG). Изменение — If-Match.
 */
@RestController
@RequestMapping("/api/v1/changes")
class ChangeController {

    static final String READERS = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST', 'AUD', 'APR')";
    static final String WORKERS = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST')";

    private final ChangeService service;

    ChangeController(ChangeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(READERS)
    CursorPage<ChangeDtos.ChangeItem> list(@RequestParam(required = false) String state,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(required = false) String risk,
                                           @RequestParam(required = false) UUID ciId,
                                           @RequestParam(required = false) UUID serviceId,
                                           @RequestParam(required = false) UUID groupId,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "false") boolean mine,
                                           @RequestParam(required = false) Instant from,
                                           @RequestParam(required = false) Instant to,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        Set<String> states = state == null || state.isBlank() ? Set.of()
                : new LinkedHashSet<>(Arrays.stream(state.split(",")).map(String::strip).toList());
        return service.list(new ChangeService.ListFilter(states, type, risk, ciId, serviceId, groupId, q, mine, from, to),
                PageParams.of(cursor, limit), Me.current());
    }

    @PostMapping
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> create(@Valid @RequestBody ChangeDtos.Create req) {
        ChangeDtos.ChangeView v = service.create(req, Me.current());
        return ResponseEntity.created(URI.create("/api/v1/changes/" + v.id())).eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/risk-questions")
    @PreAuthorize(READERS)
    List<RiskModel.Question> riskQuestions() {
        return RiskModel.QUESTIONS;
    }

    @GetMapping("/approvals/pending")
    @PreAuthorize(READERS)
    List<ChangeDtos.PendingApproval> pending() {
        return service.pendingApprovals(Me.current());
    }

    @GetMapping("/{id}")
    @PreAuthorize(READERS)
    ResponseEntity<ChangeDtos.ChangeView> get(@PathVariable UUID id) {
        return ok(service.get(id, Me.current()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> update(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody ChangeDtos.Update req) {
        return ok(service.update(id, ifMatch, req, Me.current()));
    }

    @PutMapping("/{id}/plan")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> plan(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody ChangeDtos.Plan req) {
        return ok(service.replacePlan(id, ifMatch, req, Me.current()));
    }

    @PutMapping("/{id}/risk")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> risk(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody ChangeDtos.Risk req) {
        return ok(service.assessRisk(id, ifMatch, req, Me.current()));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> submit(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody(required = false) ChangeDtos.SendToApproval req) {
        return ok(service.submit(id, ifMatch, req, Me.current()));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> returnToDraft(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                        @Valid @RequestBody ChangeDtos.Reason req) {
        return ok(service.returnToDraft(id, ifMatch, req.reason(), Me.current()));
    }

    @PostMapping("/{id}/send-to-approval")
    @PreAuthorize("hasRole('CHG')")
    ResponseEntity<ChangeDtos.ChangeView> sendToApproval(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                         @Valid @RequestBody ChangeDtos.SendToApproval req) {
        return ok(service.sendToApproval(id, ifMatch, req, Me.current()));
    }

    /** Согласование изменения подтверждается повторным входом (SEC-08, 10.8). */
    @PostMapping("/{id}/approvals/{approvalId}/decision")
    @PreAuthorize("hasAnyRole('CHG', 'APR', 'AGT', 'LEAD', 'MIM', 'PRB', 'CFG', 'AST')")
    @RequiresRecentAuth
    ResponseEntity<ChangeDtos.ChangeView> decide(@PathVariable UUID id, @PathVariable UUID approvalId,
                                                 @Valid @RequestBody ChangeDtos.Decision req) {
        return ok(service.decide(id, approvalId, req, Me.current()));
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> reschedule(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                     @Valid @RequestBody ChangeDtos.Reschedule req) {
        return ok(service.reschedule(id, ifMatch, req, Me.current()));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> start(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @Valid @RequestBody(required = false) ChangeDtos.Start req) {
        return ok(service.start(id, ifMatch, req, Me.current()));
    }

    @PostMapping("/{id}/steps/{stepId}/result")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> stepResult(@PathVariable UUID id, @PathVariable UUID stepId,
                                                     @Valid @RequestBody ChangeDtos.StepResult req) {
        return ok(service.stepResult(id, stepId, req, Me.current()));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> rollback(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                   @Valid @RequestBody ChangeDtos.Reason req) {
        return ok(service.startRollback(id, ifMatch, req.reason(), Me.current()));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> verify(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.toVerification(id, ifMatch, Me.current()));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> reopen(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.backToImplementation(id, ifMatch, Me.current()));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> complete(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                   @Valid @RequestBody ChangeDtos.Complete req) {
        return ok(service.complete(id, ifMatch, req, Me.current()));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('CHG')")
    ResponseEntity<ChangeDtos.ChangeView> close(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @Valid @RequestBody ChangeDtos.Close req) {
        return ok(service.close(id, ifMatch, req, Me.current()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(WORKERS)
    ResponseEntity<ChangeDtos.ChangeView> cancel(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody ChangeDtos.Reason req) {
        return ok(service.cancel(id, ifMatch, req.reason(), Me.current()));
    }

    @GetMapping("/{id}/conflicts")
    @PreAuthorize(READERS)
    List<ChangeDtos.Conflict> conflicts(@PathVariable UUID id) {
        return service.conflicts(id, Me.current());
    }

    /** Проверка конфликтов до сохранения окна: форма показывает их сразу (AT-06). */
    @PostMapping("/conflicts/check")
    @PreAuthorize(WORKERS)
    List<ChangeDtos.Conflict> check(@Valid @RequestBody ChangeDtos.ConflictQuery q) {
        return service.checkConflicts(q, Me.current());
    }

    @GetMapping("/{id}/impact")
    @PreAuthorize(READERS)
    ChangeDtos.Impact impact(@PathVariable UUID id, @RequestParam(defaultValue = "3") int depth) {
        return service.impact(id, depth, Me.current());
    }

    @GetMapping("/{id}/related-incidents")
    @PreAuthorize(READERS)
    List<ChangeDtos.RelatedIncident> related(@PathVariable UUID id) {
        return service.relatedIncidents(id, Me.current());
    }

    private static ResponseEntity<ChangeDtos.ChangeView> ok(ChangeDtos.ChangeView v) {
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }
}
