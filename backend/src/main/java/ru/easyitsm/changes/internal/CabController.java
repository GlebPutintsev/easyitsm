package ru.easyitsm.changes.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
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
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * REST CAB (CHG-07). Заседания видят поддержка, согласующие и аудитор; ведут менеджер изменений и председатель;
 * голосуют участники заседания.
 */
@RestController
@RequestMapping("/api/v1/cab/meetings")
class CabController {

    private final CabService service;

    CabController(CabService service) {
        this.service = service;
    }

    record MeetingRequest(@Size(min = 1, max = 200) String title, Instant startsAt,
                          @Min(5) @Max(1440) Integer durationMinutes, @Size(max = 500) String location, UUID chairId,
                          @Size(max = 50) List<UUID> memberIds, @Size(max = 50_000) String minutes) {

        CabService.MeetingInput input() {
            return new CabService.MeetingInput(title, startsAt, durationMinutes, location, chairId, memberIds, minutes);
        }
    }

    record ItemRequest(@NotNull UUID changeId) {
    }

    record VoteRequest(@NotNull @Pattern(regexp = "approve|reject|abstain") String vote, @Size(max = 4000) String comment) {
    }

    record DecisionRequest(@NotNull @Pattern(regexp = "approve|reject|defer") String decision,
                           @Size(max = 4000) String comment) {
    }

    record CompleteRequest(@Size(max = 50_000) String minutes) {
    }

    @GetMapping
    @PreAuthorize(ChangeController.READERS)
    CursorPage<CabService.MeetingItem> list(@RequestParam(required = false) @Pattern(regexp = "planned|in_progress|completed|cancelled") String state,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return service.list(state, PageParams.of(cursor, limit));
    }

    @PostMapping
    @PreAuthorize("hasRole('CHG')")
    ResponseEntity<CabService.MeetingView> create(@Valid @RequestBody MeetingRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw ChangeProblem.field("title", "Укажите тему заседания");
        }
        CabService.MeetingView v = service.create(req.input(), Me.current());
        return ResponseEntity.created(URI.create("/api/v1/cab/meetings/" + v.id())).eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/{id}")
    @PreAuthorize(ChangeController.READERS)
    ResponseEntity<CabService.MeetingView> get(@PathVariable UUID id) {
        return ok(service.get(id, Me.current()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> update(@PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                  @Valid @RequestBody MeetingRequest req) {
        return ok(service.update(id, ifMatch, req.input(), Me.current()));
    }

    /** «Собрать повестку». */
    @PostMapping("/{id}/collect")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> collect(@PathVariable UUID id,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.collect(id, ifMatch, Me.current()));
    }

    @PostMapping("/{id}/items")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> addItem(@PathVariable UUID id,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                   @Valid @RequestBody ItemRequest req) {
        return ok(service.addItem(id, ifMatch, req.changeId(), Me.current()));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> removeItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        return ok(service.removeItem(id, itemId, Me.current()));
    }

    /** «Начать заседание». */
    @PostMapping("/{id}/start")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> start(@PathVariable UUID id,
                                                 @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.start(id, ifMatch, Me.current()));
    }

    @PostMapping("/{id}/items/{itemId}/vote")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<CabService.MeetingView> vote(@PathVariable UUID id, @PathVariable UUID itemId,
                                                @Valid @RequestBody VoteRequest req) {
        return ok(service.vote(id, itemId, req.vote(), req.comment(), Me.current()));
    }

    /** «Утвердить», «Отклонить», «Отложить»: решение утверждает изменение, поэтому с повторным входом. */
    @PostMapping("/{id}/items/{itemId}/decision")
    @PreAuthorize(ChangeController.WORKERS)
    @RequiresRecentAuth
    ResponseEntity<CabService.MeetingView> decide(@PathVariable UUID id, @PathVariable UUID itemId,
                                                  @Valid @RequestBody DecisionRequest req) {
        return ok(service.decide(id, itemId, req.decision(), req.comment(), Me.current()));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> complete(@PathVariable UUID id,
                                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                    @Valid @RequestBody(required = false) CompleteRequest req) {
        return ok(service.complete(id, ifMatch, req == null ? null : req.minutes(), Me.current()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> cancel(@PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.cancel(id, ifMatch, Me.current()));
    }

    /** «Разослать протокол». */
    @PostMapping("/{id}/protocol")
    @PreAuthorize(ChangeController.WORKERS)
    ResponseEntity<CabService.MeetingView> protocol(@PathVariable UUID id,
                                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.sendProtocol(id, ifMatch, Me.current()));
    }

    private static ResponseEntity<CabService.MeetingView> ok(CabService.MeetingView v) {
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }
}
