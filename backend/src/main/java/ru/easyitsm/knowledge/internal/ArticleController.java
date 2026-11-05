package ru.easyitsm.knowledge.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import ru.easyitsm.knowledge.api.ArticleStatus;
import ru.easyitsm.knowledge.api.ArticleSummaryView;
import ru.easyitsm.knowledge.internal.KnowledgeDtos.ArticleDetails;
import ru.easyitsm.knowledge.internal.KnowledgeDtos.ArticleListItem;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.platform.web.TraceIdFilter;

/**
 * API статей. Роль проверяется здесь, доступ к конкретной статье (аудитория, владелец) — в сервисе.
 * Все изменения требуют {@code If-Match} с ETag статьи.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
class ArticleController {

    /** Роли, которым доступны создание и правка (3.2). Публикация — только KNW. */
    private static final String EDITORS = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST', 'KNW')";
    private static final String MANAGER = "hasRole('KNW')";

    private final ArticleService service;

    ArticleController(ArticleService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    List<KnowledgeDtos.TemplateView> templates() {
        return ArticleTemplates.all();
    }

    /**
     * Список статей. {@code view=portal} — опубликованные и видимые по аудитории,
     * {@code view=workspace} — рабочее место редактора со всеми статусами.
     */
    @GetMapping("/articles")
    CursorPage<ArticleListItem> list(@RequestParam(defaultValue = "portal") String view,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) UUID categoryId,
                                     @RequestParam(defaultValue = "false") boolean mine,
                                     @RequestParam(defaultValue = "false") boolean awaitingMyReview,
                                     @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        ArticleSearch.View v = switch (view) {
            case "portal" -> ArticleSearch.View.PORTAL;
            case "workspace" -> ArticleSearch.View.WORKSPACE;
            default -> throw ApiException.badRequest("invalid_view", "view: portal или workspace");
        };
        String st = null;
        if (status != null && !status.isBlank()) {
            try {
                st = ArticleStatus.fromCode(status).code();
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_status", "Неизвестный статус статьи");
            }
        }
        if (q != null && q.length() > 500) {
            throw ApiException.badRequest("query_too_long", "Слишком длинный запрос");
        }
        var filter = new ArticleSearch.Filter(v, q, st, categoryId, mine, awaitingMyReview);
        return service.list(filter, PageParams.of(cursor, limit));
    }

    /** Подсказки статей по тексту обращения (INC-05): при создании заявки и в поиске портала. */
    @GetMapping("/suggestions")
    List<ArticleSummaryView> suggestions(@RequestParam String text,
                                         @RequestParam(defaultValue = "5") int limit) {
        if (text.length() > 5000) {
            text = text.substring(0, 5000);
        }
        return service.suggest(text, limit);
    }

    @PostMapping("/articles")
    @PreAuthorize(EDITORS)
    ResponseEntity<ArticleDetails> create(@Valid @RequestBody KnowledgeDtos.CreateArticle cmd) {
        return created(service.create(cmd));
    }

    @PostMapping("/articles/from-ticket")
    @PreAuthorize(EDITORS)
    ResponseEntity<ArticleDetails> createFromTicket(@Valid @RequestBody KnowledgeDtos.CreateFromTicket cmd) {
        return created(service.createFromTicket(cmd));
    }

    /** {@code version=current} — рабочая копия для правки, иначе опубликованная версия. */
    @GetMapping("/articles/{id}")
    ResponseEntity<ArticleDetails> get(@PathVariable UUID id,
                                       @RequestParam(defaultValue = "published") String version) {
        return ok(service.get(id, "current".equals(version)));
    }

    @PatchMapping("/articles/{id}")
    @PreAuthorize(EDITORS)
    ResponseEntity<ArticleDetails> update(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                          @Valid @RequestBody KnowledgeDtos.UpdateArticle cmd) {
        return ok(service.update(id, ifMatch, cmd));
    }

    @PostMapping("/articles/{id}/submit")
    @PreAuthorize(EDITORS)
    ResponseEntity<ArticleDetails> submit(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                          @Valid @RequestBody(required = false) KnowledgeDtos.Submit cmd) {
        return ok(service.submit(id, ifMatch, cmd == null ? new KnowledgeDtos.Submit(null, null, null) : cmd));
    }

    @PostMapping("/articles/{id}/publish")
    @PreAuthorize(MANAGER)
    ResponseEntity<ArticleDetails> publish(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                           @Valid @RequestBody(required = false) KnowledgeDtos.Publish cmd) {
        return ok(service.publish(id, ifMatch, cmd == null ? new KnowledgeDtos.Publish(null, null, null, null) : cmd));
    }

    @PostMapping("/articles/{id}/reject")
    @PreAuthorize(MANAGER)
    ResponseEntity<ArticleDetails> reject(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                          @Valid @RequestBody KnowledgeDtos.Reject cmd) {
        return ok(service.reject(id, ifMatch, cmd));
    }

    @PostMapping("/articles/{id}/archive")
    @PreAuthorize(MANAGER)
    ResponseEntity<ArticleDetails> archive(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.archive(id, ifMatch));
    }

    @PostMapping("/articles/{id}/unarchive")
    @PreAuthorize(MANAGER)
    ResponseEntity<ArticleDetails> unarchive(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.unarchive(id, ifMatch));
    }

    @PostMapping("/articles/{id}/request-review")
    @PreAuthorize(MANAGER)
    ResponseEntity<ArticleDetails> requestReview(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.requestReview(id, ifMatch));
    }

    @PostMapping("/articles/{id}/confirm-review")
    @PreAuthorize(MANAGER)
    ResponseEntity<ArticleDetails> confirmReview(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody(required = false) KnowledgeDtos.ConfirmReview cmd) {
        return ok(service.confirmReview(id, ifMatch, cmd == null ? new KnowledgeDtos.ConfirmReview(null) : cmd));
    }

    @GetMapping("/articles/{id}/versions")
    List<KnowledgeDtos.VersionSummary> versions(@PathVariable UUID id) {
        return service.versions(id);
    }

    @GetMapping("/articles/{id}/versions/{no}")
    KnowledgeDtos.VersionDetails version(@PathVariable UUID id, @PathVariable int no) {
        return service.version(id, no);
    }

    @PostMapping("/articles/{id}/versions/{no}/restore")
    @PreAuthorize(EDITORS)
    ResponseEntity<ArticleDetails> restore(@PathVariable UUID id, @PathVariable int no,
                                           @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ok(service.restore(id, no, ifMatch));
    }

    /** «Помогло / Не помогло» (10.12). Повторная оценка заменяет прежнюю. */
    @PutMapping("/articles/{id}/feedback")
    KnowledgeDtos.FeedbackSummary feedback(@PathVariable UUID id, @Valid @RequestBody KnowledgeDtos.Feedback cmd) {
        return service.rate(id, cmd);
    }

    /** Найденные секреты возвращаются списком, чтобы редактор подсветил строки. */
    @ExceptionHandler(ArticleService.SecretsDetectedException.class)
    ResponseEntity<ProblemDetail> secrets(ArticleService.SecretsDetectedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setType(URI.create("https://easyitsm.ru/problems/" + ex.code()));
        pd.setTitle(ex.status().getReasonPhrase());
        pd.setProperty("code", ex.code());
        pd.setProperty("trace_id", TraceIdFilter.current());
        pd.setProperty("findings", ex.findings());
        return ResponseEntity.status(ex.status()).body(pd);
    }

    private static ResponseEntity<ArticleDetails> created(ArticleDetails d) {
        return ResponseEntity.created(URI.create("/api/v1/knowledge/articles/" + d.id())).eTag(ETags.of(d.version())).body(d);
    }

    private static ResponseEntity<ArticleDetails> ok(ArticleDetails d) {
        return ResponseEntity.ok().eTag(ETags.of(d.version())).body(d);
    }
}
