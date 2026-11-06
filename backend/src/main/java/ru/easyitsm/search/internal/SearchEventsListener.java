package ru.easyitsm.search.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import ru.easyitsm.knowledge.events.ArticleArchived;
import ru.easyitsm.knowledge.events.ArticlePublished;
import ru.easyitsm.knowledge.events.ArticleReviewDue;
import ru.easyitsm.knowledge.events.ArticleUnarchived;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.events.TicketEvent;
import ru.easyitsm.tickets.events.TicketMerged;

/**
 * Изменения заявок и статей ставят запись в очередь индексации (NFR-04: в поиске не позже чем через 10 с).
 * Как в модуле sla: после фиксации, арендатор из события, затем своя транзакция. При ошибке публикация
 * остаётся в outbox и повторяется. Без OpenSearch очередь не ведётся: пилот ищет прямо в БД.
 */
@Component
class SearchEventsListener {

    private final IndexQueue queue;
    private final SearchProperties props;
    private final TransactionTemplate tx;

    SearchEventsListener(IndexQueue queue, SearchProperties props, PlatformTransactionManager txManager) {
        this.queue = queue;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

    @TransactionalEventListener
    void on(TicketEvent e) {
        List<UUID> ids = e instanceof TicketMerged m
                ? java.util.stream.Stream.concat(java.util.stream.Stream.of(e.ticketId()), m.sourceTicketIds().stream())
                        .toList()
                : List.of(e.ticketId());
        enqueue(e.tenantId(), IndexQueue.Kind.TICKET, ids);
    }

    @TransactionalEventListener
    void on(ArticlePublished e) {
        enqueue(e.tenantId(), IndexQueue.Kind.ARTICLE, List.of(e.articleId()));
    }

    @TransactionalEventListener
    void on(ArticleArchived e) {
        enqueue(e.tenantId(), IndexQueue.Kind.ARTICLE, List.of(e.articleId()));
    }

    @TransactionalEventListener
    void on(ArticleUnarchived e) {
        enqueue(e.tenantId(), IndexQueue.Kind.ARTICLE, List.of(e.articleId()));
    }

    @TransactionalEventListener
    void on(ArticleReviewDue e) {
        enqueue(e.tenantId(), IndexQueue.Kind.ARTICLE, List.of(e.articleId()));
    }

    private void enqueue(UUID tenantId, IndexQueue.Kind kind, List<UUID> ids) {
        if (!props.opensearch().enabled()) {
            return;
        }
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(s -> queue.enqueue(kind, ids)));
    }
}
