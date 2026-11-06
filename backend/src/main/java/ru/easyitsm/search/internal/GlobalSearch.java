package ru.easyitsm.search.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.easyitsm.catalog.api.CatalogItemRef;
import ru.easyitsm.catalog.api.CatalogQueries;
import ru.easyitsm.knowledge.api.ArticleReadScope;
import ru.easyitsm.knowledge.api.ArticleSearchHit;
import ru.easyitsm.knowledge.api.ArticleSearchIndex;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.search.internal.SearchModels.Group;
import ru.easyitsm.search.internal.SearchModels.Hit;
import ru.easyitsm.search.internal.SearchModels.ResultKind;
import ru.easyitsm.tickets.api.TicketReadScope;
import ru.easyitsm.tickets.api.TicketSearchHit;
import ru.easyitsm.tickets.api.TicketSearchIndex;
import ru.easyitsm.tickets.api.TicketTextQuery;
import tools.jackson.databind.JsonNode;

/**
 * Глобальный поиск с учётом прав (TCK-15, PRT-02). Заявки и статьи — из OpenSearch, если он включён,
 * доступен и индекс арендатора построен; иначе из резервного поиска PostgreSQL модулей (AT-08). Услуги каталога —
 * всегда через {@link CatalogQueries}: каталог мал и ищется в БД с теми же правами, что на портале.
 * <p>
 * Выдачу OpenSearch перепроверяет модуль-владелец: заявка, у которой сменилась группа или убран наблюдатель,
 * пропадает из выдачи сразу, не дожидаясь переиндексации.
 */
@Service
class GlobalSearch {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearch.class);

    private final SearchProperties props;
    private final OpenSearchClient client;
    private final SearchIndex index;
    private final IndexQueue queue;
    private final TicketSearchIndex tickets;
    private final ArticleSearchIndex articles;
    private final ObjectProvider<CatalogQueries> catalog;
    private final Clock clock;
    private volatile Instant openSearchBlockedUntil = Instant.EPOCH;

    GlobalSearch(SearchProperties props, OpenSearchClient client, SearchIndex index, IndexQueue queue,
                 TicketSearchIndex tickets, ArticleSearchIndex articles, ObjectProvider<CatalogQueries> catalog,
                 Optional<Clock> clock) {
        this.props = props;
        this.client = client;
        this.index = index;
        this.queue = queue;
        this.tickets = tickets;
        this.articles = articles;
        this.catalog = catalog;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    SearchModels.Response search(SearchModels.Query q) {
        Set<ResultKind> kinds = q.kinds().isEmpty() ? EnumSet.allOf(ResultKind.class) : EnumSet.copyOf(q.kinds());
        boolean configured = props.opensearch().enabled();
        List<Group> groups = null;
        String engine = "postgres";
        if (configured && openSearchUsable()) {
            try {
                groups = openSearch(q, kinds);
                engine = "opensearch";
            } catch (OpenSearchClient.SearchUnavailable e) {
                openSearchBlockedUntil = clock.instant().plus(props.opensearch().retryAfter());
                log.warn("Поиск переключён на PostgreSQL на {}: {}", props.opensearch().retryAfter(), e.getMessage());
            }
        }
        if (groups == null) {
            groups = postgres(q, kinds);
        }
        if (kinds.contains(ResultKind.SERVICE)) {
            groups.add(services(q));
        }
        return new SearchModels.Response(engine, configured && engine.equals("postgres"), groups);
    }

    /** OpenSearch не в паузе после сбоя и индекс арендатора построен. */
    boolean openSearchUsable() {
        if (clock.instant().isBefore(openSearchBlockedUntil)) {
            return false;
        }
        return queue.state().map(s -> s.readyAt() != null).orElse(false);
    }

    private List<Group> openSearch(SearchModels.Query q, Set<ResultKind> kinds) {
        UUID tenant = TenantContext.require();
        List<Object> lines = new ArrayList<>();
        List<ResultKind> order = new ArrayList<>();
        TicketReadScope ticketScope = null;
        if (kinds.contains(ResultKind.TICKET)) {
            ticketScope = tickets.readScope();
            lines.add(Map.of("index", index.alias()));
            lines.add(OpenSearchQueries.tickets(tenant, q, ticketScope));
            order.add(ResultKind.TICKET);
        }
        if (kinds.contains(ResultKind.ARTICLE)) {
            ArticleReadScope articleScope = articles.readScope();
            lines.add(Map.of("index", index.alias()));
            lines.add(OpenSearchQueries.articles(tenant, q, articleScope));
            order.add(ResultKind.ARTICLE);
        }
        List<Group> groups = new ArrayList<>();
        if (order.isEmpty()) {
            return groups;
        }
        OpenSearchClient.Response r = client.msearch(lines);
        if (!r.ok()) {
            throw new OpenSearchClient.SearchUnavailable("msearch " + r.status() + ": " + r.body(), null);
        }
        JsonNode responses = r.body().path("responses");
        for (int i = 0; i < order.size(); i++) {
            JsonNode res = responses.path(i);
            if (res.has("error")) {
                throw new OpenSearchClient.SearchUnavailable("Ошибка поиска: " + res.path("error"), null);
            }
            groups.add(toGroup(order.get(i), res, ticketScope));
        }
        return groups;
    }

    private Group toGroup(ResultKind kind, JsonNode res, TicketReadScope ticketScope) {
        List<Hit> hits = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (JsonNode h : res.path("hits").path("hits")) {
            JsonNode src = h.path("_source");
            JsonNode hl = h.path("highlight");
            UUID id = UUID.fromString(src.path("entity_id").asString());
            String title = first(hl, "title");
            String snippet = first(hl, "body");
            if (snippet == null) {
                snippet = first(hl, "public_text");
            }
            if (snippet == null && ticketScope != null && ticketScope.seesInternal()) {
                snippet = first(hl, "internal_text");
            }
            ids.add(id);
            hits.add(new Hit(kind.code(), id, text(src, "ref"),
                    title == null ? SearchModels.plain(text(src, "title")) : SearchModels.segments(title),
                    SearchModels.segments(snippet), text(src, "ticket_type"), text(src, "status"),
                    text(src, "status_category"), text(src, "priority"),
                    src.hasNonNull("updated_at") ? Instant.parse(src.path("updated_at").asString()) : null));
        }
        Set<UUID> visible = new HashSet<>(kind == ResultKind.TICKET ? tickets.visibleAmong(ids)
                : articles.readableAmong(ids));
        List<Hit> allowed = hits.stream().filter(h -> visible.contains(h.id())).toList();
        long total = res.path("hits").path("total").path("value").asLong(allowed.size());
        return new Group(kind.code(), allowed, Math.max(allowed.size(), total - (hits.size() - allowed.size())));
    }

    private List<Group> postgres(SearchModels.Query q, Set<ResultKind> kinds) {
        List<Group> groups = new ArrayList<>();
        if (kinds.contains(ResultKind.TICKET)) {
            List<TicketSearchHit> found = tickets.search(new TicketTextQuery(q.text(), q.ticketTypes(), q.state(),
                    q.updatedFrom(), q.limit()));
            groups.add(new Group(ResultKind.TICKET.code(), found.stream().map(t -> new Hit("ticket", t.id(),
                    t.number(), SearchModels.plain(t.title()), SearchModels.segments(t.snippet()), t.type().code(),
                    t.statusCode(), t.statusCategory().code(), t.priority().code(), t.updatedAt())).toList(), null));
        }
        if (kinds.contains(ResultKind.ARTICLE)) {
            List<ArticleSearchHit> found = articles.search(q.text(), q.limit());
            groups.add(new Group(ResultKind.ARTICLE.code(), found.stream().map(a -> new Hit("article", a.id(),
                    a.key(), SearchModels.plain(a.title()), SearchModels.segments(a.snippet()), null, null, null,
                    null, a.publishedAt())).toList(), null));
        }
        return groups;
    }

    private Group services(SearchModels.Query q) {
        CatalogQueries c = catalog.getIfAvailable();
        List<CatalogItemRef> found = c == null ? List.of() : c.search(q.text().strip(), q.limit());
        return new Group(ResultKind.SERVICE.code(), found.stream().map(s -> new Hit("service", s.id(), null,
                SearchModels.plain(s.name()), SearchModels.plain(s.summary()), null, null, null, null, null))
                .toList(), null);
    }

    private static String first(JsonNode highlight, String field) {
        JsonNode arr = highlight.path(field);
        return arr.isArray() && !arr.isEmpty() ? arr.get(0).asString() : null;
    }

    private static String text(JsonNode src, String field) {
        return src.hasNonNull(field) ? src.path(field).asString() : null;
    }
}
