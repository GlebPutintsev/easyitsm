package ru.easyitsm.problems.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.catalog.api.CatalogItemRef;
import ru.easyitsm.catalog.api.CatalogQueries;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.problems.api.NewProblem;
import ru.easyitsm.problems.api.ProblemCommands;
import ru.easyitsm.problems.api.ProblemRef;
import ru.easyitsm.problems.events.KnownErrorPublished;
import ru.easyitsm.problems.internal.ProblemDtos.Analysis;
import ru.easyitsm.problems.internal.ProblemDtos.KnownErrorView;
import ru.easyitsm.problems.internal.ProblemDtos.LinkedTicket;
import ru.easyitsm.problems.internal.ProblemDtos.ProblemView;
import ru.easyitsm.problems.internal.ProblemStore.Detail;
import ru.easyitsm.problems.internal.ProblemStore.KnownError;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.Level;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketDirectory;
import ru.easyitsm.tickets.api.TicketLinks;
import ru.easyitsm.tickets.api.TicketPermissions;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketTrends;
import ru.easyitsm.tickets.api.TicketType;

/**
 * Проблемы (PRB-01…05). Видимость и право работать с проблемой и инцидентами берутся у модуля заявок
 * ({@code findVisible}, {@code permissions}): чужая проблема — 404, видимая без права работы — 403.
 */
@Service
class ProblemService implements ProblemCommands {

    private static final Set<String> SOURCES = Set.of("manual", "incident", "recurring");
    private static final Set<String> ALL_SOURCES = Set.of("manual", "incident", "recurring", "major_incident");

    private final ProblemStore store;
    private final TicketQueries tickets;
    private final TicketCommands commands;
    private final TicketLinks links;
    private final TicketTrends trends;
    private final TicketDirectory ticketDirectory;
    private final CatalogQueries catalog;
    private final UserDirectory directory;
    private final IncidentResolver resolver;
    private final ApplicationEventPublisher events;

    ProblemService(ProblemStore store, TicketQueries tickets, TicketCommands commands, TicketLinks links,
                   TicketTrends trends, TicketDirectory ticketDirectory, CatalogQueries catalog,
                   UserDirectory directory, IncidentResolver resolver, ApplicationEventPublisher events) {
        this.store = store;
        this.tickets = tickets;
        this.commands = commands;
        this.links = links;
        this.trends = trends;
        this.ticketDirectory = ticketDirectory;
        this.catalog = catalog;
        this.directory = directory;
        this.resolver = resolver;
        this.events = events;
    }

    // ================================================================ создание

    @Transactional
    ProblemView create(ProblemDtos.CreateProblem in) {
        UUID me = Me.id();
        List<TicketSnapshot> incidents = visibleIncidents(in.incidentIds());
        String source = in.source() == null ? (incidents.isEmpty() ? "manual" : "incident") : in.source();
        if (!SOURCES.contains(source)) {
            throw ApiException.badRequest("invalid_source", "source: manual, incident или recurring");
        }
        if (in.groupId() != null && directory.findGroup(in.groupId()).isEmpty()) {
            throw invalidAssignment();
        }
        TicketSnapshot first = incidents.isEmpty() ? null : incidents.getFirst();
        UUID serviceId = in.serviceId() != null ? in.serviceId() : first == null ? null : first.serviceId();
        UUID ciId = in.ciId() != null ? in.ciId() : first == null ? null : first.ciId();
        NewTicket ticket = new NewTicket(TicketType.PROBLEM, in.title().strip(), in.description(), me, null, null,
                Channel.AGENT, level(in.impact()), level(in.urgency()), serviceId, ciId,
                first == null ? null : first.categoryId(), null, in.groupId(), null, Map.of(), false, me);
        TicketRef ref = commands.create(ticket);
        store.insert(ref.id(), source, "", new Analysis(timelineOf(incidents), List.of(), Map.of(), ""), me);
        for (TicketSnapshot incident : incidents) {
            linkIncident(ref.id(), incident.id(), me);
        }
        return view(ref.id());
    }

    @Override
    @Transactional
    public ProblemRef create(NewProblem p) {
        if (!ALL_SOURCES.contains(p.source())) {
            throw ApiException.badRequest("invalid_source", "Неизвестный источник проблемы");
        }
        List<TicketSnapshot> incidents = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(p.incidentIds())) {
            TicketSnapshot t = tickets.find(id).orElseThrow(() -> ApiException.notFound("Инцидент"));
            requireIncident(t);
            incidents.add(t);
        }
        NewTicket ticket = new NewTicket(TicketType.PROBLEM, p.title(), p.description(), p.requesterId(), null,
                null, Channel.AGENT, null, null, p.serviceId(), p.ciId(), null, null, p.groupId(), null, Map.of(),
                false, p.requesterId());
        TicketRef ref = commands.create(ticket);
        List<ProblemDtos.TimelineEntry> timeline = new ArrayList<>(timelineOf(incidents));
        p.timeline().forEach(t -> timeline.add(new ProblemDtos.TimelineEntry(t.at(), t.text())));
        timeline.sort(java.util.Comparator.comparing(ProblemDtos.TimelineEntry::at,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        store.insert(ref.id(), p.source(), p.rootCause(),
                new Analysis(timeline.stream().limit(200).toList(), List.of(), Map.of(), ""), p.requesterId());
        for (TicketSnapshot incident : incidents) {
            linkIncident(ref.id(), incident.id(), p.requesterId());
        }
        return new ProblemRef(ref.id(), ref.number());
    }

    // ================================================================ карточка

    @Transactional(readOnly = true)
    ProblemView get(String ref) {
        return view(visibleProblem(ref).id());
    }

    @Transactional
    ProblemView updateAnalysis(String ref, String ifMatch, ProblemDtos.UpdateAnalysis in) {
        TicketPermissions p = requireWork(ref);
        UUID me = Me.id();
        Detail d = store.lock(p.ticket().id(), me);
        ETags.requireMatch(ifMatch, d.version());
        Analysis analysis = in.analysis() == null ? d.analysis() : validate(in.analysis().normalized());
        store.update(new Detail(d.ticketId(), d.source(), in.rootCause() == null ? d.rootCause() : in.rootCause(),
                in.workaround() == null ? d.workaround() : in.workaround(), analysis,
                in.closeIncidents() == null ? d.closeIncidents() : in.closeIncidents(), d.riskAcceptedBy(),
                d.riskAcceptedAt(), d.riskReviewAt(), d.riskReason(), d.version()), me);
        return view(d.ticketId());
    }

    /** Принять риск без исправления: кто, почему, когда пересмотреть (7.4 risk_accepted_by, risk_review_at). */
    @Transactional
    ProblemView acceptRisk(String ref, String ifMatch, ProblemDtos.AcceptRisk in) {
        Me.requireAny(Me.PROBLEM_MANAGERS);
        TicketPermissions p = requireWork(ref);
        UUID me = Me.id();
        Detail d = store.lock(p.ticket().id(), me);
        ETags.requireMatch(ifMatch, d.version());
        Instant review = in.reviewAt() != null ? in.reviewAt() : Instant.now().plus(Duration.ofDays(90));
        store.update(new Detail(d.ticketId(), d.source(), d.rootCause(), d.workaround(), d.analysis(),
                d.closeIncidents(), me, Db.now(), review, in.reason().strip(), d.version()), me);
        return view(d.ticketId());
    }

    @Transactional
    ProblemView linkIncidents(String ref, ProblemDtos.LinkIncidents in) {
        TicketPermissions p = requireWork(ref);
        UUID me = Me.id();
        for (TicketSnapshot incident : visibleIncidents(in.incidentIds())) {
            linkIncident(p.ticket().id(), incident.id(), me);
        }
        return view(p.ticket().id());
    }

    /** Изменение для исправления (PRB-04): заявка CHANGE, связь с проблемой, модуль changes заведёт карточку. */
    @Transactional
    LinkedTicket createChange(String ref, ProblemDtos.CreateChange in) {
        TicketPermissions p = requireWork(ref);
        UUID me = Me.id();
        TicketSnapshot problem = p.ticket();
        String description = in.description() == null || in.description().isBlank()
                ? "Исправление проблемы " + problem.number() + ": " + problem.title()
                : in.description();
        TicketRef change = commands.create(new NewTicket(TicketType.CHANGE, in.title().strip(), description, me,
                null, null, Channel.AGENT, null, null, problem.serviceId(), problem.ciId(), null, null, null, null,
                Map.of(), false, me));
        links.link(change.id(), problem.id(), "relates_to", me);
        store.addChange(problem.id(), change.id(), me);
        return linked(change.id(), true);
    }

    @Transactional
    ProblemDtos.ResolveResult resolveIncidents(String ref, ProblemDtos.ResolveIncidents in) {
        TicketPermissions p = requireWork(ref);
        StatusCategory c = p.ticket().statusCategory();
        if (c != StatusCategory.RESOLVED && c != StatusCategory.CLOSED) {
            throw ApiException.conflict("problem_not_resolved", "Сначала решите проблему");
        }
        return resolver.resolveAll(p.ticket(), in == null ? null : in.comment());
    }

    // ================================================================ известная ошибка

    @Transactional
    ProblemView saveKnownError(String ref, String ifMatch, ProblemDtos.SaveKnownError in) {
        TicketPermissions p = requireWork(ref);
        UUID me = Me.id();
        Optional<KnownError> existing = store.knownErrorOf(p.ticket().id(), true);
        if (existing.isPresent()) {
            ETags.requireMatch(ifMatch, existing.get().version());
            store.updateKnownError(existing.get().id(), in, me);
        } else {
            store.lock(p.ticket().id(), me);
            store.insertKnownError(p.ticket().id(), in, me);
            if (p.ticket().statusCategory() == StatusCategory.RESOLVED
                    || p.ticket().statusCategory() == StatusCategory.CLOSED) {
                store.markFixed(p.ticket().id(), true);
            }
        }
        return view(p.ticket().id());
    }

    @Transactional
    ProblemView publishKnownError(String ref, String ifMatch, boolean publish) {
        Me.requireAny(Me.PROBLEM_MANAGERS);
        TicketPermissions p = requireWork(ref);
        UUID me = Me.id();
        KnownError ke = store.knownErrorOf(p.ticket().id(), true)
                .orElseThrow(() -> ApiException.notFound("Известная ошибка"));
        ETags.requireMatch(ifMatch, ke.version());
        if (ke.published() != publish) {
            store.publishKnownError(ke.id(), publish, me);
            if (publish) {
                events.publishEvent(new KnownErrorPublished(TenantContext.require(), Instant.now(), ke.id(),
                        p.ticket().id(), me));
            }
        }
        return view(p.ticket().id());
    }

    /**
     * Известные ошибки для агента (PRB-03, INC-05): по заявке — совпадение КЕ, услуги, категории и слов темы;
     * иначе — по строке поиска. Черновики видят только менеджеры проблем.
     */
    @Transactional(readOnly = true)
    List<KnownErrorView> knownErrors(String q, UUID ticketId, UUID serviceId, UUID ciId, UUID categoryId,
                                     boolean includeDrafts, int limit) {
        boolean drafts = includeDrafts && Me.hasAny(Me.PROBLEM_MANAGERS);
        int max = Math.clamp(limit, 1, 50);
        if (q != null && q.length() > 500) {
            throw ApiException.badRequest("query_too_long", "Слишком длинный запрос");
        }
        List<KnownError> found;
        if (ticketId != null) {
            TicketSnapshot t = tickets.findVisible(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
            found = store.search(t.title(), t.serviceId() != null ? t.serviceId() : t.catalogItemId(), t.ciId(),
                    t.categoryId(), drafts, true, max);
        } else {
            boolean any = (q != null && !q.isBlank()) || serviceId != null || ciId != null || categoryId != null;
            found = store.search(q, serviceId, ciId, categoryId, drafts, any, max);
        }
        Map<UUID, String> numbers = new LinkedHashMap<>();
        return found.stream().map(k -> knownErrorView(k, numbers)).toList();
    }

    // ================================================================ повторяющиеся инциденты (PRB-05)

    @Transactional(readOnly = true)
    List<ProblemDtos.RecurringCluster> recurring(String dimension, int days, int minCount, int limit) {
        TicketTrends.Dimension dim;
        try {
            dim = TicketTrends.Dimension.valueOf(dimension.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_dimension", "dimension: service, category или ci");
        }
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(Math.clamp(days, 1, 366)));
        var clusters = trends.recurringIncidents(dim, from, to, Math.clamp(minCount, 2, 1000), Math.clamp(limit, 1,
                100));
        Map<UUID, String> names = names(dim);
        Set<UUID> all = clusters.stream().flatMap(c -> c.ticketIds().stream()).collect(Collectors.toSet());
        Map<UUID, List<UUID>> problemsByIncident = store.problemsOf(all);
        Map<UUID, String> numbers = new LinkedHashMap<>();
        List<ProblemDtos.RecurringCluster> out = new ArrayList<>();
        for (var c : clusters) {
            List<UUID> visible = new ArrayList<>();
            List<LinkedTicket> sample = new ArrayList<>();
            for (UUID id : c.ticketIds()) {
                Optional<TicketSnapshot> t = tickets.findVisible(id);
                if (t.isPresent()) {
                    visible.add(id);
                    if (sample.size() < 5) {
                        sample.add(linked(t.get(), true));
                    }
                }
            }
            Set<UUID> problemIds = new LinkedHashSet<>();
            c.ticketIds().forEach(id -> problemIds.addAll(problemsByIncident.getOrDefault(id, List.of())));
            List<ProblemDtos.ProblemRefView> problems = problemIds.stream()
                    .map(id -> new ProblemDtos.ProblemRefView(id, numberOf(id, numbers)))
                    .filter(r -> r.number() != null)
                    .toList();
            out.add(new ProblemDtos.RecurringCluster(dim.name().toLowerCase(java.util.Locale.ROOT), c.key(),
                    names.get(c.key()), c.count(), c.open(), c.firstAt(), c.lastAt(), visible, sample, problems));
        }
        return out;
    }

    private Map<UUID, String> names(TicketTrends.Dimension dim) {
        return switch (dim) {
            case SERVICE -> catalog.activeItems().stream()
                    .collect(Collectors.toMap(CatalogItemRef::id, CatalogItemRef::name, (a, b) -> a));
            case CATEGORY -> ticketDirectory.categories().stream()
                    .collect(Collectors.toMap(TicketDirectory.CategoryRef::id, TicketDirectory.CategoryRef::name,
                            (a, b) -> a));
            case CI -> Map.of(); // имена КЕ — модуль cmdb (волна 3), пока интерфейс показывает id
        };
    }

    // ================================================================ события заявок

    /** Проблема решена: известная ошибка — исправлена, инциденты — решены, если так настроено (PRB-04). */
    int onProblemResolved(TicketSnapshot problem) {
        store.markFixed(problem.id(), true);
        Detail d = store.find(problem.id()).orElse(Detail.empty(problem.id()));
        if (!d.closeIncidents()) {
            return 0;
        }
        return resolver.resolveAll(problem, null).resolved().size();
    }

    void onProblemReopened(UUID problemId) {
        store.markFixed(problemId, false);
    }

    /** Изменение выполнено: заметка в ленте проблемы. @return проблемы, где это изменение было исправлением */
    List<UUID> onChangeCompleted(TicketSnapshot change) {
        List<UUID> problems = store.completeChange(change.id(), Db.now());
        for (UUID problemId : problems) {
            commands.addComment(problemId, null, "Изменение " + change.number() + " «" + change.title()
                    + "» выполнено. Проверьте исправление и решите проблему.", ru.easyitsm.tickets.api.Visibility.INTERNAL);
        }
        return problems;
    }

    // ================================================================ сборка ответа

    private ProblemView view(UUID problemId) {
        TicketPermissions p = tickets.permissions(problemId).orElseThrow(() -> ApiException.notFound("Проблема"));
        TicketSnapshot t = p.ticket();
        Detail d = store.find(problemId).orElse(Detail.empty(problemId));
        List<LinkedTicket> incidents = new ArrayList<>();
        for (TicketLinks.TicketLinkRef l : links.links(problemId)) {
            if ("causes".equals(l.kind())) {
                incidents.add(linked(l.otherId(), false));
            }
        }
        List<LinkedTicket> changes = store.changes(problemId).stream().map(id -> linked(id, false)).toList();
        Map<UUID, String> numbers = new LinkedHashMap<>(Map.of(problemId, t.number()));
        KnownErrorView ke = store.knownErrorOf(problemId, false).map(k -> knownErrorView(k, numbers)).orElse(null);
        boolean work = p.canWork() && !p.closed();
        boolean manager = work && Me.hasAny(Me.PROBLEM_MANAGERS);
        boolean resolvable = t.statusCategory() == StatusCategory.RESOLVED
                || t.statusCategory() == StatusCategory.CLOSED;
        var risk = d.riskAcceptedBy() == null ? null
                : new ProblemDtos.Risk(d.riskAcceptedBy(), d.riskAcceptedAt(), d.riskReviewAt(), d.riskReason());
        return new ProblemView(t.id(), t.number(), t.title(), t.statusCode(), t.statusCategory().code(),
                t.priority().code(), t.groupId(), t.assigneeId(), t.serviceId(), t.ciId(), t.categoryId(),
                t.createdAt(), t.resolvedAt(), d.source(), d.rootCause(), d.workaround(), d.analysis(),
                d.closeIncidents(), risk, ke, incidents, changes,
                new ProblemDtos.Permissions(work, manager, work, p.canWork() && resolvable), d.version());
    }

    private KnownErrorView knownErrorView(KnownError k, Map<UUID, String> numbers) {
        return new KnownErrorView(k.id(), k.problemId(), numberOf(k.problemId(), numbers), k.title(), k.symptoms(),
                k.workaround(), k.serviceId(), k.ciId(), k.categoryId(), k.published(), k.publishedAt(), k.fixed(),
                k.updatedAt(), k.version());
    }

    private String numberOf(UUID ticketId, Map<UUID, String> cache) {
        return cache.computeIfAbsent(ticketId, id -> tickets.find(id).map(TicketSnapshot::number).orElse(null));
    }

    /** Связанная заявка: невидимая показывается только номером. */
    private LinkedTicket linked(UUID id, boolean knownVisible) {
        Optional<TicketSnapshot> visible = tickets.findVisible(id);
        if (visible.isPresent()) {
            return linked(visible.get(), true);
        }
        return tickets.find(id).map(t -> new LinkedTicket(t.id(), t.number(), null, null, null, null, null, false))
                .orElse(new LinkedTicket(id, null, null, null, null, null, null, false));
    }

    private static LinkedTicket linked(TicketSnapshot t, boolean visible) {
        return new LinkedTicket(t.id(), t.number(), t.title(), t.statusCode(), t.statusCategory().code(),
                t.priority().code(), t.createdAt(), visible);
    }

    // ================================================================ проверки

    private void linkIncident(UUID problemId, UUID incidentId, UUID actor) {
        links.link(incidentId, problemId, "caused_by", actor);
        store.addIncident(problemId, incidentId, actor);
    }

    private TicketSnapshot visibleProblem(String ref) {
        UUID id = parseUuid(ref);
        Optional<TicketSnapshot> t = id != null ? tickets.findVisible(id)
                : tickets.findByNumber(ref).flatMap(s -> tickets.findVisible(s.id()));
        return t.filter(s -> s.type() == TicketType.PROBLEM).orElseThrow(() -> ApiException.notFound("Проблема"));
    }

    /** Проблема, с которой пользователь работает: иначе 404 или 403; закрытая — 409 {@code ticket_closed}. */
    private TicketPermissions requireWork(String ref) {
        TicketSnapshot t = visibleProblem(ref);
        TicketPermissions p = tickets.permissions(t.id()).orElseThrow(() -> ApiException.notFound("Проблема"));
        if (!p.canWork()) {
            throw ApiException.forbidden();
        }
        if (p.closed()) {
            throw ApiException.conflict("ticket_closed", "Проблема закрыта");
        }
        return p;
    }

    private List<TicketSnapshot> visibleIncidents(List<UUID> ids) {
        List<TicketSnapshot> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (UUID id : new LinkedHashSet<>(ids)) {
            TicketSnapshot t = tickets.findVisible(id).orElseThrow(() -> ApiException.notFound("Инцидент"));
            requireIncident(t);
            out.add(t);
        }
        return out;
    }

    private static void requireIncident(TicketSnapshot t) {
        if (t.type() != TicketType.INCIDENT) {
            throw ApiException.badRequest("not_incident", "С проблемой связываются только инциденты: " + t.number());
        }
    }

    private static List<ProblemDtos.TimelineEntry> timelineOf(List<TicketSnapshot> incidents) {
        return incidents.stream()
                .sorted(java.util.Comparator.comparing(TicketSnapshot::createdAt))
                .limit(50)
                .map(i -> new ProblemDtos.TimelineEntry(i.createdAt(), "Инцидент " + i.number() + ": " + i.title()))
                .toList();
    }

    /** Исикава — только известные категории, до 20 причин в каждой; хронология — по времени. */
    private static Analysis validate(Analysis a) {
        Map<String, List<String>> fish = new LinkedHashMap<>();
        for (var e : a.ishikawa().entrySet()) {
            if (!ProblemDtos.ISHIKAWA.contains(e.getKey())) {
                throw ApiException.badRequest("invalid_ishikawa", "Неизвестная категория диаграммы: " + e.getKey());
            }
            List<String> causes = e.getValue() == null ? List.of()
                    : e.getValue().stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
            if (causes.size() > 20 || causes.stream().anyMatch(s -> s.length() > 500)) {
                throw ApiException.badRequest("invalid_ishikawa", "До 20 причин по 500 символов в категории");
            }
            fish.put(e.getKey(), causes);
        }
        List<ProblemDtos.TimelineEntry> timeline = a.timeline().stream()
                .filter(t -> t.text() != null && !t.text().isBlank())
                .sorted(java.util.Comparator.comparing(ProblemDtos.TimelineEntry::at,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        return new Analysis(timeline, a.whys(), fish, a.conclusion());
    }

    private static Level level(String code) {
        return code == null || code.isBlank() ? null : Level.fromCode(code);
    }

    private static ApiException invalidAssignment() {
        ApiException e = new ApiException(HttpStatus.BAD_REQUEST, "invalid_assignment", "Группа не найдена");
        return e;
    }

    static UUID parseUuid(String ref) {
        try {
            return ref != null && ref.length() == 36 ? UUID.fromString(ref) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
