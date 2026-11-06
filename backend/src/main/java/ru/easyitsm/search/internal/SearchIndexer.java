package ru.easyitsm.search.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.easyitsm.knowledge.api.ArticleSearchIndex;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.TicketSearchIndex;

/**
 * Отправляет очередь индексации в OpenSearch и загружает индекс целиком: при первом запуске, после
 * пересоздания индекса, по ночной сверке и по команде администратора. Документы берёт через api модулей
 * в арендаторе записи. Если OpenSearch недоступен, записи остаются в очереди и повторяются с паузой.
 */
@Component
class SearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);
    private static final int ID_PAGE = 1000;

    private final IndexQueue queue;
    private final SearchIndex index;
    private final SearchProperties props;
    private final TicketSearchIndex tickets;
    private final ArticleSearchIndex articles;
    private volatile boolean ensured;

    SearchIndexer(IndexQueue queue, SearchIndex index, SearchProperties props, TicketSearchIndex tickets,
                  ArticleSearchIndex articles) {
        this.queue = queue;
        this.index = index;
        this.props = props;
        this.tickets = tickets;
        this.articles = articles;
    }

    boolean enabled() {
        return props.opensearch().enabled();
    }

    /**
     * Один проход: убедиться, что индекс есть, забрать пачку и отправить её.
     *
     * @return сколько записей отправлено успешно
     */
    int runOnce() {
        if (!enabled()) {
            return 0;
        }
        try {
            ensureIndex();
        } catch (OpenSearchClient.SearchUnavailable e) {
            log.debug("OpenSearch недоступен, индексация отложена: {}", e.getMessage());
            return 0;
        }
        List<IndexQueue.Claimed> claimed = queue.claim(props.indexing().batchSize(), props.indexing().lease());
        if (claimed.isEmpty()) {
            return 0;
        }
        Map<UUID, List<IndexQueue.Claimed>> byTenant = new LinkedHashMap<>();
        for (IndexQueue.Claimed c : claimed) {
            byTenant.computeIfAbsent(c.tenantId(), k -> new ArrayList<>()).add(c);
        }
        int sent = 0;
        for (var e : byTenant.entrySet()) {
            int[] n = {0};
            TenantContext.runAs(e.getKey(), () -> n[0] = send(e.getKey(), e.getValue()));
            sent += n[0];
        }
        return sent;
    }

    private int send(UUID tenantId, List<IndexQueue.Claimed> batch) {
        Instant now = Instant.now();
        List<SearchIndex.Op> ops = new ArrayList<>(batch.size());
        List<IndexQueue.Claimed> opItems = new ArrayList<>(batch.size());
        for (IndexQueue.Claimed c : batch) {
            try {
                String id = SearchDocuments.id(tenantId, c.kind(), c.entityId());
                Map<String, Object> doc = switch (c.kind()) {
                    case TICKET -> tickets.document(c.entityId())
                            .map(t -> SearchDocuments.ticket(tenantId, t, now)).orElse(null);
                    case ARTICLE -> articles.document(c.entityId())
                            .map(a -> SearchDocuments.article(tenantId, a, now)).orElse(null);
                };
                ops.add(new SearchIndex.Op(id, doc));
                opItems.add(c);
            } catch (RuntimeException ex) {
                log.warn("Не удалось собрать документ {} {}: {}", c.kind().code(), c.entityId(), ex.getMessage());
                queue.failed(c, ex.getMessage());
            }
        }
        int ok = 0;
        try {
            List<SearchIndex.OpResult> results = index.bulk(ops);
            for (int i = 0; i < results.size(); i++) {
                SearchIndex.OpResult r = results.get(i);
                if (r.ok()) {
                    queue.done(opItems.get(i));
                    ok++;
                } else {
                    if (r.error() != null && r.error().contains("index_not_found")) {
                        ensured = false;
                    }
                    queue.failed(opItems.get(i), r.error());
                }
            }
        } catch (OpenSearchClient.SearchUnavailable ex) {
            log.warn("OpenSearch не принял пачку из {} записей: {}", ops.size(), ex.getMessage());
            for (IndexQueue.Claimed c : opItems) {
                queue.failed(c, ex.getMessage());
            }
            return 0;
        }
        queue.markIndexed(queue.drained());
        return ok;
    }

    /** Индекс есть; если он только что создан или у арендатора нет состояния — полная загрузка. */
    void ensureIndex() {
        if (ensured) {
            return;
        }
        boolean created = index.ensure();
        for (UUID tenant : queue.tenants()) {
            TenantContext.runAs(tenant, () -> {
                if (created || queue.state().isEmpty()) {
                    enqueueAll(true);
                }
            });
        }
        ensured = true;
    }

    /** Ставит в очередь все заявки и статьи текущего арендатора. */
    void enqueueAll(boolean resetReady) {
        queue.requestFull(index.alias(), resetReady);
        int tickets = enqueuePaged(IndexQueue.Kind.TICKET, this.tickets::idsAfter);
        int articles = enqueuePaged(IndexQueue.Kind.ARTICLE, this.articles::idsAfter);
        log.info("Арендатор {}: в очередь индексации поставлено заявок {}, статей {}", TenantContext.require(),
                tickets, articles);
        if (tickets + articles == 0) {
            queue.markIndexed(queue.drained());
        }
    }

    /** Ночная сверка: индекс остаётся рабочим, записи переотправляются. */
    void reconcileAll() {
        if (!enabled()) {
            return;
        }
        for (UUID tenant : queue.tenants()) {
            TenantContext.runAs(tenant, () -> enqueueAll(false));
        }
    }

    private int enqueuePaged(IndexQueue.Kind kind, BiFunction<UUID, Integer, List<UUID>> page) {
        int total = 0;
        UUID after = null;
        while (true) {
            List<UUID> ids = page.apply(after, ID_PAGE);
            if (ids.isEmpty()) {
                return total;
            }
            queue.enqueue(kind, ids);
            total += ids.size();
            after = ids.getLast();
            if (ids.size() < ID_PAGE) {
                return total;
            }
        }
    }
}
