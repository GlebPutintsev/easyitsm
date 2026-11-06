package ru.easyitsm.search.internal;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.search.internal.SearchModels.ResultKind;
import ru.easyitsm.tickets.api.TicketType;

/** Глобальный поиск: {@code GET /api/v1/search?q=…&types=ticket,article,service}. */
@RestController
@RequestMapping("/api/v1/search")
class SearchController {

    private static final int MAX_QUERY = 200;

    private final GlobalSearch search;
    private final SearchIndexer indexer;
    private final IndexQueue queue;
    private final OpenSearchClient client;
    private final SearchIndex index;
    private final SearchProperties props;

    SearchController(GlobalSearch search, SearchIndexer indexer, IndexQueue queue, OpenSearchClient client,
                     SearchIndex index, SearchProperties props) {
        this.search = search;
        this.indexer = indexer;
        this.queue = queue;
        this.client = client;
        this.index = index;
        this.props = props;
    }

    /**
     * @param types      виды через запятую: ticket, article, service; по умолчанию все
     * @param ticketType типы заявок через запятую: incident, request…
     * @param state      заявки: active или closed
     */
    @GetMapping
    SearchModels.Response search(@RequestParam("q") String q,
                                 @RequestParam(required = false) String types,
                                 @RequestParam(required = false) String ticketType,
                                 @RequestParam(required = false) String state,
                                 @RequestParam(required = false) Instant updatedFrom,
                                 @RequestParam(required = false) Integer limit) {
        String text = q == null ? "" : q.strip();
        if (text.length() < 2) {
            throw ApiException.badRequest("query_too_short", "Введите хотя бы два символа");
        }
        if (text.length() > MAX_QUERY) {
            throw ApiException.badRequest("query_too_long", "Запрос длиннее " + MAX_QUERY + " символов");
        }
        if (state != null && !state.equals("active") && !state.equals("closed")) {
            throw ApiException.badRequest("invalid_state", "state: active или closed");
        }
        int l = limit == null ? 5 : Math.max(1, Math.min(limit, props.maxLimit()));
        Set<ResultKind> kinds = EnumSet.noneOf(ResultKind.class);
        Set<TicketType> ticketTypes = EnumSet.noneOf(TicketType.class);
        try {
            if (types != null && !types.isBlank()) {
                Arrays.stream(types.split(",")).filter(s -> !s.isBlank()).map(ResultKind::fromCode).forEach(kinds::add);
            }
            if (ticketType != null && !ticketType.isBlank()) {
                Arrays.stream(ticketType.split(",")).filter(s -> !s.isBlank()).map(s -> TicketType.fromCode(s.strip()))
                        .forEach(ticketTypes::add);
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_filter", "Неизвестный вид или тип: " + e.getMessage());
        }
        return search.search(new SearchModels.Query(text, kinds, ticketTypes, state, updatedFrom, l));
    }

    /**
     * Состояние поиска для администратора.
     *
     * @param ready  индекс арендатора построен и поиск идёт в OpenSearch
     * @param pending записей в очереди индексации
     */
    record Status(boolean openSearchEnabled, boolean openSearchAvailable, String index, boolean ready,
                  Instant fullRequestedAt, Instant lastIndexedAt, long pending, String lastError) {
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADM', 'SEC')")
    Status status() {
        boolean enabled = props.opensearch().enabled();
        boolean available = false;
        if (enabled) {
            try {
                available = client.get("/_cluster/health").ok();
            } catch (OpenSearchClient.SearchUnavailable e) {
                available = false;
            }
        }
        var state = queue.state();
        return new Status(enabled, available, index.alias(), state.map(s -> s.readyAt() != null).orElse(false),
                state.map(IndexQueue.State::fullRequestedAt).orElse(null),
                state.map(IndexQueue.State::lastIndexedAt).orElse(null), queue.pending(),
                queue.lastError().orElse(null));
    }

    /** Полная переиндексация арендатора из БД. Поиск продолжает работать по текущему индексу. */
    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADM')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void reindex() {
        if (!props.opensearch().enabled()) {
            throw ApiException.conflict("search_engine_disabled", "OpenSearch не подключён: поиск идёт по БД");
        }
        TenantContext.require();
        indexer.enqueueAll(false);
    }
}
