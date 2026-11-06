package ru.easyitsm.knowledge.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.knowledge.api.ArticleAudience;
import ru.easyitsm.knowledge.api.ArticleQueries;
import ru.easyitsm.knowledge.api.ArticleStatus;
import ru.easyitsm.knowledge.api.ArticleSummaryView;
import ru.easyitsm.knowledge.events.ArticleArchived;
import ru.easyitsm.knowledge.events.ArticleCreatedFromTicket;
import ru.easyitsm.knowledge.events.ArticlePublished;
import ru.easyitsm.knowledge.events.ArticleReturnedToDraft;
import ru.easyitsm.knowledge.events.ArticleReviewDue;
import ru.easyitsm.knowledge.events.ArticleReviewReminder;
import ru.easyitsm.knowledge.events.ArticleSubmittedForReview;
import ru.easyitsm.knowledge.internal.KnowledgeDtos.ArticleDetails;
import ru.easyitsm.knowledge.internal.KnowledgeDtos.ArticleListItem;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Статьи базы знаний: правка, версии, жизненный цикл (5.7) и чтение с учётом аудитории (KB-04).
 * <p>
 * Чужая или невидимая статья — 404 (AT-01). Видимая, но действие не разрешено — 403.
 * Действие не подходит к статусу — 409 {@code invalid_transition}.
 */
@Service
class ArticleService implements ArticleQueries {

    static final String SECRETS_DETECTED = "secrets_detected";
    static final String TICKET_NOT_RESOLVED = "ticket_not_resolved";
    private static final int MAX_TITLE = 255;

    private final ArticleRepository articles;
    private final ArticleVersionRepository versions;
    private final KbCategoryRepository categories;
    private final ArticleFeedbackRepository feedback;
    private final ArticleSearch search;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final KnowledgeProperties props;
    private final TicketQueries tickets;
    private final TicketCommands ticketCommands;
    private final Clock clock;

    ArticleService(ArticleRepository articles, ArticleVersionRepository versions, KbCategoryRepository categories,
                   ArticleFeedbackRepository feedback, ArticleSearch search, JdbcClient jdbc,
                   ApplicationEventPublisher events, KnowledgeProperties props, TicketQueries tickets,
                   TicketCommands ticketCommands, Optional<Clock> clock) {
        this.articles = articles;
        this.versions = versions;
        this.categories = categories;
        this.feedback = feedback;
        this.search = search;
        this.jdbc = jdbc;
        this.events = events;
        this.props = props;
        this.tickets = tickets;
        this.ticketCommands = ticketCommands;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ---------------------------------------------------------------- api для других модулей

    @Override
    @Transactional(readOnly = true)
    public List<ArticleSummaryView> suggest(String text, int limit) {
        int l = Math.max(1, Math.min(limit, 10));
        return search.suggest(Reader.current(), text, l).stream().map(ArticleService::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ru.easyitsm.knowledge.api.ArticlePermissions> permissions(UUID articleId) {
        Reader r = Reader.current();
        return articles.findById(articleId).filter(a -> ArticleAccess.canRead(r, a))
                .map(a -> new ru.easyitsm.knowledge.api.ArticlePermissions(a.getId(), a.key(), true,
                        ArticleAccess.canEdit(r, a)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ArticleSummaryView> findReadable(UUID articleId) {
        Reader r = Reader.current();
        return articles.findById(articleId)
                .filter(a -> ArticleAccess.canReadPublished(r, a))
                .map(a -> {
                    ArticleVersion v = version(a, a.publishedVersionNo());
                    return new ArticleSummaryView(a.getId(), a.key(), a.publishedTitle(),
                            Snippets.plain(v.body(), ArticleSearch.SNIPPET_LENGTH), a.status().code(),
                            a.audience().code(), a.categoryId(), a.publishedAt(), a.getUpdatedAt());
                });
    }

    // ---------------------------------------------------------------- чтение

    @Transactional(readOnly = true)
    public CursorPage<ArticleListItem> list(ArticleSearch.Filter filter, PageParams page) {
        Reader r = Reader.current();
        if (filter.view() == ArticleSearch.View.WORKSPACE && !r.editor() && !r.seesEverything()) {
            throw ApiException.forbidden();
        }
        boolean searching = ArticleSearch.tsQuery(filter.q(), " & ", true) != null;
        KeysetCursor after = searching ? null : page.keyset();
        Instant afterSortAt = after == null ? null : Instant.parse(after.sortValue());
        List<ArticleSearch.Row> rows = search.page(r, filter, afterSortAt, after == null ? null : after.id(),
                searching ? page.limit() : page.limit() + 1);
        if (searching) {
            return new CursorPage<>(rows.stream().map(ArticleSearch.Row::item).toList(), null);
        }
        return CursorPage.of(rows, page.limit(), row -> new KeysetCursor(row.sortAt().toString(), row.item().id()))
                .map(ArticleSearch.Row::item);
    }

    /**
     * Статья для чтения или правки. {@code wantCurrent} — рабочая копия (нужен доступ к ней),
     * иначе опубликованная версия, а если её нет — рабочая копия для тех, кому она доступна.
     */
    @Transactional(readOnly = true)
    public ArticleDetails get(UUID id, boolean wantCurrent) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        boolean workspace = ArticleAccess.canReadWorkspace(r, a);
        int shown;
        if (wantCurrent) {
            if (!workspace) {
                throw ApiException.notFound("Статья");
            }
            shown = a.currentVersionNo();
        } else if (ArticleAccess.canReadPublished(r, a)) {
            shown = a.publishedVersionNo();
        } else {
            shown = a.currentVersionNo();
        }
        return details(r, a, version(a, shown), workspace);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDtos.VersionSummary> versions(UUID id) {
        Reader r = Reader.current();
        Article a = findWorkspace(r, id);
        return versions.findAllOf(a.getId()).stream()
                .map(v -> new KnowledgeDtos.VersionSummary(v.versionNo(), v.title(), v.notes(), v.restoredFrom(),
                        isPublished(a, v), v.versionNo() == a.currentVersionNo(), v.getCreatedAt(), v.getCreatedBy(),
                        v.getUpdatedAt(), v.getUpdatedBy()))
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.VersionDetails version(UUID id, int versionNo) {
        Reader r = Reader.current();
        Article a = findWorkspace(r, id);
        ArticleVersion v = versions.findByArticleIdAndVersionNo(a.getId(), versionNo)
                .orElseThrow(() -> ApiException.notFound("Версия"));
        return new KnowledgeDtos.VersionDetails(v.versionNo(), v.title(), v.body(), v.notes(), v.restoredFrom(),
                isPublished(a, v), v.versionNo() == a.currentVersionNo(), v.getCreatedAt(), v.getCreatedBy(),
                v.getUpdatedAt(), v.getUpdatedBy());
    }

    // ---------------------------------------------------------------- создание и правка

    @Transactional
    public ArticleDetails create(KnowledgeDtos.CreateArticle cmd) {
        Reader r = requireEditor();
        String body = cmd.body() != null ? cmd.body() : ArticleTemplates.body(cmd.template());
        ArticleAudience audience = cmd.audience() == null ? ArticleAudience.EMPLOYEES
                : ArticleAudience.fromCode(cmd.audience());
        Article a = newArticle(r, cmd.title(), body, checkCategory(cmd.categoryId()), audience, cmd.audienceGroupId(),
                null, null);
        return details(r, a, version(a, 1), true);
    }

    /**
     * KB-05: черновик из решённой заявки, видимый агентам, пока редактор не решит иначе.
     * <p>
     * Заявку проверяет модуль заявок ({@link TicketQueries#findVisible}): она должна быть видна автору
     * (иначе 404) и решена или закрыта (иначе 409 {@code ticket_not_resolved}). Номер и тема по умолчанию
     * берутся из заявки, текст разделов — из формы: описание и решение агент правит перед созданием.
     * В ленту заявки добавляется внутренняя заметка со ссылкой на статью.
     */
    @Transactional
    public ArticleDetails createFromTicket(KnowledgeDtos.CreateFromTicket cmd) {
        Reader r = requireEditor();
        TicketSnapshot ticket = tickets.findVisible(cmd.ticketId()).orElseThrow(() -> ApiException.notFound("Заявка"));
        if (ticket.statusCategory() != StatusCategory.RESOLVED && ticket.statusCategory() != StatusCategory.CLOSED) {
            throw ApiException.conflict(TICKET_NOT_RESOLVED,
                    "Статью можно создать только из решённой или закрытой заявки");
        }
        String title = cmd.title() == null || cmd.title().isBlank() ? ticket.title() : cmd.title();
        title = title.strip();
        if (title.length() > MAX_TITLE) {
            title = title.substring(0, MAX_TITLE);
        }
        String body = ArticleTemplates.fromTicket(cmd.symptoms(), cmd.cause(), cmd.solution());
        Article a = newArticle(r, title, body, checkCategory(cmd.categoryId()), ArticleAudience.AGENTS, null,
                ticket.id(), ticket.number());
        ticketCommands.addComment(ticket.id(), r.userId(), feedNote(a), Visibility.INTERNAL);
        events.publishEvent(new ArticleCreatedFromTicket(a.getTenantId(), a.getId(), a.key(), ticket.id(),
                r.userId()));
        return details(r, a, version(a, 1), true);
    }

    /** Заметка в ленте заявки. Ссылка относительная: адрес рабочего места знает только клиент. */
    static String feedNote(Article a) {
        return "Создан черновик статьи базы знаний [" + a.key() + " · " + a.title().replace("]", ")")
                + "](/agent/kb/" + a.getId() + ")";
    }

    @Transactional
    public ArticleDetails update(UUID id, String ifMatch, KnowledgeDtos.UpdateArticle cmd) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        a.requireEditable();
        if (!ArticleAccess.canEdit(r, a)) {
            throw ApiException.forbidden();
        }
        if (cmd.ownerId() != null && !r.manager()) {
            throw ApiException.forbidden();
        }

        ArticleVersion v;
        if (a.currentVersionIsMutable()) {
            v = version(a, a.currentVersionNo());
        } else {
            ArticleVersion base = version(a, a.currentVersionNo());
            int next = a.startNewVersion();
            v = new ArticleVersion(a.getId(), next, base.title(), base.body());
        }
        if (cmd.title() != null) {
            v.setTitle(cmd.title());
            a.setTitle(cmd.title());
        }
        if (cmd.body() != null) {
            v.setBody(cmd.body());
        }
        if (Boolean.TRUE.equals(cmd.clearCategory())) {
            a.setCategoryId(null);
        } else if (cmd.categoryId() != null) {
            a.setCategoryId(checkCategory(cmd.categoryId()));
        }
        if (cmd.audience() != null) {
            a.setAudience(ArticleAudience.fromCode(cmd.audience()), cmd.audienceGroupId());
        }
        if (cmd.ownerId() != null) {
            a.setOwnerId(cmd.ownerId());
        }
        v = versions.save(v);
        a = articles.save(a);
        return details(r, a, v, true);
    }

    /** Восстановление версии: её текст становится новой рабочей версией (10.12). */
    @Transactional
    public ArticleDetails restore(UUID id, int versionNo, String ifMatch) {
        Reader r = Reader.current();
        Article a = findWorkspace(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        a.requireEditable();
        if (!ArticleAccess.canEdit(r, a)) {
            throw ApiException.forbidden();
        }
        ArticleVersion source = versions.findByArticleIdAndVersionNo(a.getId(), versionNo)
                .orElseThrow(() -> ApiException.notFound("Версия"));
        int next = a.startNewVersion();
        ArticleVersion v = new ArticleVersion(a.getId(), next, source.title(), source.body());
        v.setRestoredFrom(versionNo);
        v.setNotes("Восстановлено из версии " + versionNo);
        a.setTitle(source.title());
        v = versions.save(v);
        a = articles.save(a);
        return details(r, a, v, true);
    }

    // ---------------------------------------------------------------- жизненный цикл

    @Transactional
    public ArticleDetails submit(UUID id, String ifMatch, KnowledgeDtos.Submit cmd) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (!ArticleAccess.canReadWorkspace(r, a) || !r.editor()) {
            throw ApiException.forbidden();
        }
        ArticleVersion v = version(a, a.currentVersionNo());
        var findings = SecretScanner.scan(v.title(), v.body());
        if (!findings.isEmpty() && !Boolean.TRUE.equals(cmd.confirmNoSecrets())) {
            throw new SecretsDetectedException(findings);
        }
        a.submit(cmd.reviewerId());
        if (cmd.comment() != null && !cmd.comment().isBlank()) {
            v.setNotes(cmd.comment().strip());
            versions.save(v);
        }
        a = articles.save(a);
        events.publishEvent(new ArticleSubmittedForReview(a.getTenantId(), a.getId(), a.key(), a.title(),
                a.currentVersionNo(), a.reviewerId(), r.userId()));
        return details(r, a, v, true);
    }

    @Transactional
    public ArticleDetails publish(UUID id, String ifMatch, KnowledgeDtos.Publish cmd) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (!r.manager()) {
            throw ApiException.forbidden();
        }
        if (a.is(ArticleStatus.DRAFT) && props.fourEyes()) {
            throw ApiException.conflict("invalid_transition", "Сначала отправьте статью на проверку");
        }
        ArticleVersion v = version(a, a.currentVersionNo());
        if (props.fourEyes() && v.authoredBy(r.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "four_eyes",
                    "Автор не может опубликовать свою статью: нужна проверка другого редактора");
        }
        if (cmd.audience() != null) {
            a.setAudience(ArticleAudience.fromCode(cmd.audience()), cmd.audienceGroupId());
        }
        Instant now = clock.instant();
        Instant reviewAt = cmd.reviewAt() != null ? cmd.reviewAt() : defaultReviewAt(now);
        a.publish(now, reviewAt);
        if (cmd.notes() != null && !cmd.notes().isBlank()) {
            v.setNotes(cmd.notes().strip());
            versions.save(v);
        }
        a = articles.save(a);
        articles.refreshSearchVector(a.getId());
        events.publishEvent(new ArticlePublished(a.getTenantId(), a.getId(), a.key(), a.publishedVersionNo(),
                a.publishedTitle(), a.audience().code(), r.userId()));
        return details(r, a, v, true);
    }

    @Transactional
    public ArticleDetails reject(UUID id, String ifMatch, KnowledgeDtos.Reject cmd) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (!r.manager()) {
            throw ApiException.forbidden();
        }
        a.reject(cmd.comment().strip());
        a = articles.save(a);
        events.publishEvent(new ArticleReturnedToDraft(a.getTenantId(), a.getId(), a.key(), a.title(),
                a.ownerId(), a.reviewComment(), r.userId()));
        return details(r, a, version(a, a.currentVersionNo()), true);
    }

    @Transactional
    public ArticleDetails archive(UUID id, String ifMatch) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (!r.manager()) {
            throw ApiException.forbidden();
        }
        a.archive();
        a = articles.save(a);
        articles.clearSearchVector(a.getId());
        events.publishEvent(new ArticleArchived(a.getTenantId(), a.getId(), a.key(), r.userId()));
        return details(r, a, version(a, a.currentVersionNo()), true);
    }

    @Transactional
    public ArticleDetails unarchive(UUID id, String ifMatch) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (!r.manager()) {
            throw ApiException.forbidden();
        }
        a.unarchive();
        a = articles.save(a);
        events.publishEvent(new ru.easyitsm.knowledge.events.ArticleUnarchived(a.getTenantId(), a.getId(), a.key(),
                r.userId()));
        return details(r, a, version(a, a.currentVersionNo()), true);
    }

    @Transactional
    public ArticleDetails requestReview(UUID id, String ifMatch) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (!r.manager()) {
            throw ApiException.forbidden();
        }
        a.markReviewDue();
        a = articles.save(a);
        events.publishEvent(new ArticleReviewDue(a.getTenantId(), a.getId(), a.key(), a.publishedTitle(),
                a.ownerId(), a.reviewAt()));
        return details(r, a, version(a, a.publishedVersionNo()), true);
    }

    @Transactional
    public ArticleDetails confirmReview(UUID id, String ifMatch, KnowledgeDtos.ConfirmReview cmd) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (!r.manager()) {
            throw ApiException.forbidden();
        }
        Instant now = clock.instant();
        a.confirmReview(now, cmd.reviewAt() != null ? cmd.reviewAt() : defaultReviewAt(now));
        a = articles.save(a);
        return details(r, a, version(a, a.publishedVersionNo()), true);
    }

    @Transactional
    public KnowledgeDtos.FeedbackSummary rate(UUID id, KnowledgeDtos.Feedback cmd) {
        Reader r = Reader.current();
        Article a = findReadable(r, id);
        if (!ArticleAccess.canReadPublished(r, a)) {
            throw ApiException.conflict("not_published", "Оценить можно только опубликованную статью");
        }
        ArticleFeedback f = feedback.findByArticleIdAndUserId(a.getId(), r.userId())
                .orElseGet(() -> new ArticleFeedback(a.getId(), r.userId()));
        f.rate(cmd.helpful(), cmd.comment() == null ? null : cmd.comment().strip());
        feedback.save(f);
        return feedbackSummary(a, r);
    }

    // ---------------------------------------------------------------- фоновая задача пересмотра

    /** Срок пересмотра наступил: статья «На пересмотре», владелец получает событие. Вызывается под арендатором. */
    @Transactional
    public void markReviewDueBySchedule(UUID id) {
        articles.findById(id).filter(a -> a.is(ArticleStatus.PUBLISHED)).ifPresent(a -> {
            a.markReviewDue();
            articles.save(a);
            events.publishEvent(new ArticleReviewDue(a.getTenantId(), a.getId(), a.key(), a.publishedTitle(),
                    a.ownerId(), a.reviewAt()));
        });
    }

    @Transactional
    public void remindReviewBySchedule(UUID id) {
        articles.findById(id).filter(a -> a.is(ArticleStatus.PUBLISHED)).ifPresent(a -> {
            a.markReminded(clock.instant());
            articles.save(a);
            events.publishEvent(new ArticleReviewReminder(a.getTenantId(), a.getId(), a.key(), a.publishedTitle(),
                    a.ownerId(), a.reviewAt()));
        });
    }

    // ---------------------------------------------------------------- служебное

    private Article newArticle(Reader r, String title, String body, UUID categoryId, ArticleAudience audience,
                               UUID groupId, UUID ticketId, String ticketRef) {
        long number = articles.nextNumber();
        Article a = new Article(number, title.strip(), categoryId, audience, groupId, r.userId());
        a.setSource(ticketId, ticketRef);
        a = articles.save(a);
        versions.save(new ArticleVersion(a.getId(), 1, a.title(), body));
        return a;
    }

    private Reader requireEditor() {
        Reader r = Reader.current();
        if (!r.editor()) {
            throw ApiException.forbidden();
        }
        return r;
    }

    private UUID checkCategory(UUID categoryId) {
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw ApiException.badRequest("unknown_category", "Категория не найдена");
        }
        return categoryId;
    }

    /** Статья, которую пользователь может хотя бы читать. Иначе 404, даже если она существует. */
    private Article findReadable(Reader r, UUID id) {
        return articles.findById(id).filter(a -> ArticleAccess.canRead(r, a))
                .orElseThrow(() -> ApiException.notFound("Статья"));
    }

    private Article findWorkspace(Reader r, UUID id) {
        Article a = findReadable(r, id);
        if (!ArticleAccess.canReadWorkspace(r, a)) {
            throw ApiException.forbidden();
        }
        return a;
    }

    private ArticleVersion version(Article a, int versionNo) {
        return versions.findByArticleIdAndVersionNo(a.getId(), versionNo)
                .orElseThrow(() -> new IllegalStateException("Нет версии " + versionNo + " статьи " + a.getId()));
    }

    private static boolean isPublished(Article a, ArticleVersion v) {
        return a.publishedVersionNo() != null && a.publishedVersionNo() == v.versionNo();
    }

    private Instant defaultReviewAt(Instant now) {
        return now.atOffset(ZoneOffset.UTC).plusMonths(props.reviewMonths()).toInstant();
    }

    private KnowledgeDtos.FeedbackSummary feedbackSummary(Article a, Reader r) {
        var row = jdbc.sql("""
                        select count(*) filter (where helpful) as helpful,
                               count(*) filter (where not helpful) as not_helpful,
                               bool_or(helpful) filter (where user_id = :me) as mine
                          from article_feedback where article_id = :id""")
                .param("id", a.getId()).param("me", r.userId())
                .query((rs, n) -> new KnowledgeDtos.FeedbackSummary(rs.getLong("helpful"), rs.getLong("not_helpful"),
                        (Boolean) rs.getObject("mine")))
                .single();
        return row;
    }

    private ArticleDetails details(Reader r, Article a, ArticleVersion shown, boolean workspace) {
        boolean ws = workspace && ArticleAccess.canReadWorkspace(r, a);
        return new ArticleDetails(
                a.getId(), a.key(), a.status().code(), shown.title(), shown.body(), shown.versionNo(),
                ws ? a.currentVersionNo() : null,
                a.publishedVersionNo(),
                a.categoryId(), a.audience().code(),
                ws ? a.audienceGroupId() : null,
                ws ? a.ownerId() : null,
                ws ? a.reviewerId() : null,
                ws ? a.reviewComment() : null,
                ws ? a.reviewAt() : null,
                a.publishedAt(),
                ws ? a.sourceTicketId() : null,
                ws ? a.sourceTicketRef() : null,
                a.getCreatedAt(),
                ws ? a.getCreatedBy() : null,
                a.getUpdatedAt(),
                ws ? a.getUpdatedBy() : null,
                a.getVersion(),
                props.fourEyes(),
                feedbackSummary(a, r),
                ArticleAccess.permissions(r, a, props.fourEyes()));
    }

    private static ArticleSummaryView toSummary(ArticleListItem i) {
        return new ArticleSummaryView(i.id(), i.key(), i.title(), i.snippet(), i.status(), i.audience(),
                i.categoryId(), i.publishedAt(), i.updatedAt());
    }

    /** 422 со списком найденных признаков секретов: автор убирает их или подтверждает отправку. */
    static final class SecretsDetectedException extends ApiException {

        private final transient List<KnowledgeDtos.SecretFinding> findings;

        SecretsDetectedException(List<KnowledgeDtos.SecretFinding> findings) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, SECRETS_DETECTED,
                    "В тексте похоже есть пароли или ключи. Уберите их или подтвердите отправку");
            this.findings = List.copyOf(findings);
        }

        List<KnowledgeDtos.SecretFinding> findings() {
            return findings;
        }
    }
}
