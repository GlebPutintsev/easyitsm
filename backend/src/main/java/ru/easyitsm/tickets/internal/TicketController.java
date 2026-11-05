package ru.easyitsm.tickets.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.tickets.api.EscalationKind;
import ru.easyitsm.tickets.api.SecretFinding;
import ru.easyitsm.tickets.api.SecretScanner;
import ru.easyitsm.tickets.api.TicketType;

/** REST заявок: {@code /api/v1/tickets/**}, контракт docs/api/tickets.yaml. Права проверяются в сервисах. */
@RestController
@RequestMapping("/api/v1/tickets")
class TicketController {

    private final TicketService tickets;
    private final TicketSearch search;
    private final TicketRelations relations;
    private final TicketMerge merge;
    private final TicketBulk bulk;
    private final TicketViews views;
    private final Feed feed;
    private final TicketAccess access;
    private final SecretScanner secrets;

    TicketController(TicketService tickets, TicketSearch search, TicketRelations relations, TicketMerge merge,
                     TicketBulk bulk, TicketViews views, Feed feed, TicketAccess access, SecretScanner secrets) {
        this.tickets = tickets;
        this.search = search;
        this.relations = relations;
        this.merge = merge;
        this.bulk = bulk;
        this.views = views;
        this.feed = feed;
        this.access = access;
        this.secrets = secrets;
    }

    // ---------------------------------------------------------------- список и карточка

    @GetMapping
    CursorPage<TicketViews.TicketView> list(@RequestParam(required = false) String scope,
                                            @RequestParam(required = false) String state,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String statusCategory,
                                            @RequestParam(required = false) String priority,
                                            @RequestParam(required = false) String groupId,
                                            @RequestParam(required = false) String assigneeId,
                                            @RequestParam(required = false) UUID requesterId,
                                            @RequestParam(required = false) UUID serviceId,
                                            @RequestParam(required = false) UUID categoryId,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) Instant createdFrom,
                                            @RequestParam(required = false) Instant createdTo,
                                            @RequestParam(required = false) Instant updatedFrom,
                            @RequestParam(required = false) Instant dueBefore,
                                            @RequestParam(required = false) Boolean awaitingAgent,
                                            @RequestParam(required = false) List<String> custom,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        var filter = new TicketSearch.Filter(scope, state, type, status, statusCategory, priority, groupId, assigneeId,
                requesterId, serviceId, categoryId, q, createdFrom, createdTo, updatedFrom, dueBefore, awaitingAgent, custom);
        var mapper = views.mapper();
        return search.page(filter, sort, PageParams.of(cursor, limit), Actor.current()).map(mapper::view);
    }

    @GetMapping("/count")
    Map<String, Long> count(@RequestParam(required = false) String scope,
                            @RequestParam(required = false) String state,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String statusCategory,
                            @RequestParam(required = false) String priority,
                            @RequestParam(required = false) String groupId,
                            @RequestParam(required = false) String assigneeId,
                            @RequestParam(required = false) UUID requesterId,
                            @RequestParam(required = false) UUID serviceId,
                            @RequestParam(required = false) UUID categoryId,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) Instant createdFrom,
                            @RequestParam(required = false) Instant createdTo,
                            @RequestParam(required = false) Instant updatedFrom,
                            @RequestParam(required = false) Instant dueBefore,
                            @RequestParam(required = false) Boolean awaitingAgent,
                            @RequestParam(required = false) List<String> custom) {
        var filter = new TicketSearch.Filter(scope, state, type, status, statusCategory, priority, groupId, assigneeId,
                requesterId, serviceId, categoryId, q, createdFrom, createdTo, updatedFrom, dueBefore, awaitingAgent, custom);
        return Map.of("count", search.count(filter, Actor.current()));
    }

    @GetMapping("/similar")
    List<TicketViews.TicketView> similar(@RequestParam String q,
                                         @RequestParam(defaultValue = "incident") String type,
                                         @RequestParam(defaultValue = "5") int limit) {
        if (limit < 1 || limit > 20) {
            throw ru.easyitsm.platform.web.ApiException.badRequest("invalid_limit", "limit от 1 до 20");
        }
        var mapper = views.mapper();
        return search.similar(q, TicketType.fromCode(type), limit, Actor.current()).stream().map(mapper::view).toList();
    }

    @PostMapping
    ResponseEntity<TicketViews.TicketDetails> create(@Valid @RequestBody TicketRequests.Create req) {
        Actor actor = Actor.current();
        TicketRow t = tickets.createFromRest(req, actor);
        return ResponseEntity.created(URI.create("/api/v1/tickets/" + t.id))
                .eTag(ETags.of(t.version))
                .body(views.details(t, actor));
    }

    @GetMapping("/{ref}")
    ResponseEntity<TicketViews.TicketDetails> get(@PathVariable String ref) {
        Actor actor = Actor.current();
        TicketRow t = readTx(ref, actor);
        return ok(t, actor);
    }

    @PatchMapping("/{ref}")
    ResponseEntity<TicketViews.TicketDetails> update(@PathVariable String ref,
                                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                     @Valid @RequestBody TicketRequests.Update req) {
        Actor actor = Actor.current();
        return ok(tickets.update(ref, ifMatch, req, actor), actor);
    }

    // ---------------------------------------------------------------- статус

    @GetMapping("/{ref}/transitions")
    List<TicketViews.AvailableTransition> transitions(@PathVariable String ref) {
        Actor actor = Actor.current();
        return views.details(readTx(ref, actor), actor).transitions();
    }

    @PostMapping("/{ref}/transitions")
    ResponseEntity<TicketViews.TicketDetails> transition(@PathVariable String ref,
                                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                         @Valid @RequestBody TicketRequests.Transition req) {
        Actor actor = Actor.current();
        return ok(tickets.transition(ref, ifMatch, req, actor), actor);
    }

    @PostMapping("/{ref}/take")
    ResponseEntity<TicketViews.TicketDetails> take(@PathVariable String ref,
                                                   @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Actor actor = Actor.current();
        return ok(tickets.take(ref, ifMatch, actor), actor);
    }

    @PostMapping("/take-next")
    ResponseEntity<TicketViews.TicketDetails> takeNext(@RequestBody(required = false) TicketRequests.TakeNext req) {
        Actor actor = Actor.current();
        return tickets.takeNext(req == null ? null : req.groupId(), actor)
                .map(t -> ok(t, actor))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{ref}/escalate")
    ResponseEntity<TicketViews.TicketDetails> escalate(@PathVariable String ref,
                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                       @Valid @RequestBody TicketRequests.Escalate req) {
        Actor actor = Actor.current();
        return ok(tickets.escalate(ref, ifMatch, EscalationKind.fromCode(req.kind()), req.groupId(), req.reason(), actor),
                actor);
    }

    @PostMapping("/{ref}/identity-check")
    ResponseEntity<TicketViews.TicketDetails> identityCheck(@PathVariable String ref,
                                                            @Valid @RequestBody TicketRequests.IdentityCheck req) {
        Actor actor = Actor.current();
        return ok(tickets.verifyIdentity(ref, req, actor), actor);
    }

    // ---------------------------------------------------------------- лента

    @GetMapping("/{ref}/activity")
    CursorPage<Feed.Entry> activity(@PathVariable String ref,
                                    @RequestParam(required = false) String visibility,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        Actor actor = Actor.current();
        TicketRow t = readTx(ref, actor);
        if (visibility != null && !visibility.equals("public") && !visibility.equals("internal")) {
            throw ru.easyitsm.platform.web.ApiException.badRequest("invalid_visibility", "visibility: public или internal");
        }
        return feed.page(t.id, actor.canSeeInternal(), visibility, PageParams.of(cursor, limit));
    }

    @PostMapping("/{ref}/activity")
    ResponseEntity<Feed.Entry> comment(@PathVariable String ref, @Valid @RequestBody TicketRequests.Comment req) {
        Feed.Entry e = tickets.comment(ref, req, Actor.current());
        return ResponseEntity.status(201).body(e);
    }

    @GetMapping("/{ref}/history")
    CursorPage<Feed.Change> history(@PathVariable String ref,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        Actor actor = Actor.current();
        TicketRow t = readTx(ref, actor);
        if (!actor.canSeeInternal()) {
            throw ru.easyitsm.platform.web.ApiException.forbidden();
        }
        return feed.historyPage(t.id, PageParams.of(cursor, limit));
    }

    // ---------------------------------------------------------------- связи, подзадачи, наблюдатели

    @GetMapping("/{ref}/links")
    List<TicketRelations.LinkView> links(@PathVariable String ref) {
        return relations.links(ref, Actor.current());
    }

    @PostMapping("/{ref}/links")
    ResponseEntity<TicketRelations.LinkView> link(@PathVariable String ref, @Valid @RequestBody TicketRequests.Link req) {
        return ResponseEntity.status(201).body(relations.link(ref, req, Actor.current()));
    }

    @DeleteMapping("/{ref}/links/{linkId}")
    ResponseEntity<Void> unlink(@PathVariable String ref, @PathVariable UUID linkId) {
        relations.unlink(ref, linkId, Actor.current());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ref}/subtasks")
    List<TicketViews.TicketView> subtasks(@PathVariable String ref) {
        var mapper = views.mapper();
        return relations.subtasks(ref, Actor.current()).stream().map(mapper::view).toList();
    }

    @PostMapping("/{ref}/subtasks")
    ResponseEntity<TicketViews.TicketDetails> subtask(@PathVariable String ref,
                                                      @Valid @RequestBody TicketRequests.Subtask req) {
        Actor actor = Actor.current();
        TicketRow t = relations.addSubtask(ref, req, actor);
        return ResponseEntity.created(URI.create("/api/v1/tickets/" + t.id)).eTag(ETags.of(t.version))
                .body(views.details(t, actor));
    }

    @GetMapping("/{ref}/watchers")
    List<Watchers.Watcher> watchers(@PathVariable String ref) {
        return relations.watchers(ref, Actor.current());
    }

    @PutMapping("/{ref}/watchers/{user}")
    ResponseEntity<Void> watch(@PathVariable String ref, @PathVariable String user) {
        relations.watch(ref, user, Actor.current());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{ref}/watchers/{user}")
    ResponseEntity<Void> unwatch(@PathVariable String ref, @PathVariable String user) {
        relations.unwatch(ref, user, Actor.current());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- слияние и массовые операции

    @PostMapping("/{ref}/merge")
    TicketMerge.Result merge(@PathVariable String ref, @Valid @RequestBody TicketRequests.Merge req) {
        return merge.merge(ref, req.sourceIds(), Boolean.TRUE.equals(req.preview()), Actor.current());
    }

    @PostMapping("/bulk")
    TicketBulk.Result bulk(@Valid @RequestBody TicketRequests.Bulk req) {
        return bulk.run(req, Actor.current());
    }

    @PostMapping("/bulk/{operationId}/undo")
    TicketBulk.Result undo(@PathVariable UUID operationId) {
        return bulk.undo(operationId, Actor.current());
    }

    // ---------------------------------------------------------------- секреты

    record ScanResult(List<SecretFinding> findings, String masked) {
    }

    @PostMapping("/secret-scan")
    ScanResult scan(@Valid @RequestBody TicketRequests.SecretScan req) {
        Actor.current();
        return new ScanResult(secrets.scan(req.text()), secrets.mask(req.text()));
    }

    // ----------------------------------------------------------------

    private TicketRow readTx(String ref, Actor actor) {
        return tickets.readTx(ref, actor);
    }

    private ResponseEntity<TicketViews.TicketDetails> ok(TicketRow t, Actor actor) {
        return ResponseEntity.ok().eTag(ETags.of(t.version)).body(views.details(t, actor));
    }
}
