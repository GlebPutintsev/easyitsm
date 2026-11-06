package ru.easyitsm.problems.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.problems.internal.ProblemDtos.KnownErrorView;
import ru.easyitsm.problems.internal.ProblemDtos.ProblemView;

/**
 * REST проблем (PRB-01…05). Список проблем — {@code GET /api/v1/tickets?type=problem} модуля заявок, здесь —
 * данные проблемы сверх заявки. {@code {ref}} — UUID или номер {@code PRB-000123}.
 * ETag карточки — версия данных проблемы; известная ошибка — своя версия (её {@code version} в ответе).
 */
@RestController
@RequestMapping("/api/v1")
class ProblemController {

    /** Поддержка (3.2): проблему из инцидента создаёт агент (10.7). */
    static final String SUPPORT = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST')";
    static final String READERS = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST', 'AUD')";
    static final String MANAGERS = "hasAnyRole('PRB', 'LEAD', 'MIM')";
    static final String ANALYSTS = "hasAnyRole('PRB', 'LEAD', 'MIM', 'ANL')";

    private final ProblemService service;

    ProblemController(ProblemService service) {
        this.service = service;
    }

    @PostMapping("/problems")
    @PreAuthorize(SUPPORT)
    ResponseEntity<ProblemView> create(@Valid @RequestBody ProblemDtos.CreateProblem in) {
        ProblemView v = service.create(in);
        return ResponseEntity.created(URI.create("/api/v1/problems/" + v.number()))
                .eTag(ETags.of(v.version())).body(v);
    }

    @GetMapping("/problems/{ref}")
    @PreAuthorize(READERS)
    ResponseEntity<ProblemView> get(@PathVariable String ref) {
        return ok(service.get(ref));
    }

    @PutMapping("/problems/{ref}/analysis")
    @PreAuthorize(SUPPORT)
    ResponseEntity<ProblemView> analysis(@PathVariable String ref,
                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                         @Valid @RequestBody ProblemDtos.UpdateAnalysis in) {
        return ok(service.updateAnalysis(ref, ifMatch, in));
    }

    @PostMapping("/problems/{ref}/accept-risk")
    @PreAuthorize(MANAGERS)
    ResponseEntity<ProblemView> acceptRisk(@PathVariable String ref,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @Valid @RequestBody ProblemDtos.AcceptRisk in) {
        return ok(service.acceptRisk(ref, ifMatch, in));
    }

    @PostMapping("/problems/{ref}/incidents")
    @PreAuthorize(SUPPORT)
    ResponseEntity<ProblemView> linkIncidents(@PathVariable String ref,
                                              @Valid @RequestBody ProblemDtos.LinkIncidents in) {
        return ok(service.linkIncidents(ref, in));
    }

    @PostMapping("/problems/{ref}/changes")
    @PreAuthorize(SUPPORT)
    ResponseEntity<ProblemDtos.LinkedTicket> createChange(@PathVariable String ref,
                                                          @Valid @RequestBody ProblemDtos.CreateChange in) {
        var change = service.createChange(ref, in);
        return ResponseEntity.created(URI.create("/api/v1/tickets/" + change.number())).body(change);
    }

    @PostMapping("/problems/{ref}/resolve-incidents")
    @PreAuthorize(SUPPORT)
    ProblemDtos.ResolveResult resolveIncidents(@PathVariable String ref,
                                               @Valid @RequestBody(required = false) ProblemDtos.ResolveIncidents in) {
        return service.resolveIncidents(ref, in);
    }

    @PutMapping("/problems/{ref}/known-error")
    @PreAuthorize(SUPPORT)
    ResponseEntity<ProblemView> saveKnownError(@PathVariable String ref,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody ProblemDtos.SaveKnownError in) {
        return ok(service.saveKnownError(ref, ifMatch, in));
    }

    /** If-Match — версия известной ошибки. */
    @PostMapping("/problems/{ref}/known-error/publish")
    @PreAuthorize(MANAGERS)
    ResponseEntity<ProblemView> publish(@PathVariable String ref,
                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.publishKnownError(ref, ifMatch, true));
    }

    @PostMapping("/problems/{ref}/known-error/unpublish")
    @PreAuthorize(MANAGERS)
    ResponseEntity<ProblemView> unpublish(@PathVariable String ref,
                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.publishKnownError(ref, ifMatch, false));
    }

    /** Повторяющиеся инциденты (PRB-05): группы по услуге, категории или КЕ за последние {@code days} дней. */
    @GetMapping("/problems/recurring")
    @PreAuthorize(ANALYSTS)
    List<ProblemDtos.RecurringCluster> recurring(@RequestParam(defaultValue = "service") String dimension,
                                                 @RequestParam(defaultValue = "30") int days,
                                                 @RequestParam(defaultValue = "3") int minCount,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return service.recurring(dimension, days, minCount, limit);
    }

    /**
     * Известные ошибки для агента (PRB-03): {@code ticketId} — подбор к заявке, иначе поиск по тексту и полям.
     * Черновики ({@code includeDrafts}) видят только PRB, LEAD, MIM.
     */
    @GetMapping("/known-errors")
    @PreAuthorize(READERS)
    List<KnownErrorView> knownErrors(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) UUID ticketId,
                                     @RequestParam(required = false) UUID serviceId,
                                     @RequestParam(required = false) UUID ciId,
                                     @RequestParam(required = false) UUID categoryId,
                                     @RequestParam(defaultValue = "false") boolean includeDrafts,
                                     @RequestParam(defaultValue = "10") int limit) {
        return service.knownErrors(q, ticketId, serviceId, ciId, categoryId, includeDrafts, limit);
    }

    private static ResponseEntity<ProblemView> ok(ProblemView v) {
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }
}
