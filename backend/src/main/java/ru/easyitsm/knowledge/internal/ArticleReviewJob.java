package ru.easyitsm.knowledge.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Сроки пересмотра статей (KB-03, 5.7): напоминание владельцу заранее и перевод в «На пересмотре» в срок.
 * Очередь по всем арендаторам отдаёт функция {@code knowledge_review_queue}, а изменение каждой статьи
 * выполняется под её арендатором.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class ArticleReviewJob {

    private static final Logger log = LoggerFactory.getLogger(ArticleReviewJob.class);
    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final ArticleService service;
    private final KnowledgeProperties props;

    ArticleReviewJob(JdbcClient jdbc, ArticleService service, KnowledgeProperties props) {
        this.jdbc = jdbc;
        this.service = service;
        this.props = props;
    }

    private record Item(UUID tenantId, UUID articleId, String kind) {
    }

    @Scheduled(fixedDelayString = "${easyitsm.knowledge.review-check-interval:PT1H}", initialDelayString = "PT2M")
    void run() {
        var items = jdbc.sql("select tenant_id, article_id, kind from knowledge_review_queue(:days, :max)")
                .param("days", props.reminderDays()).param("max", BATCH)
                .query((rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .list();
        int done = 0;
        for (Item item : items) {
            try {
                TenantContext.runAs(item.tenantId(), () -> {
                    if ("due".equals(item.kind())) {
                        service.markReviewDueBySchedule(item.articleId());
                    } else {
                        service.remindReviewBySchedule(item.articleId());
                    }
                });
                done++;
            } catch (RuntimeException e) {
                log.warn("Пересмотр статьи {} не обработан: {}", item.articleId(), e.getClass().getSimpleName());
            }
        }
        if (done > 0) {
            log.info("Обработано сроков пересмотра статей: {}", done);
        }
    }
}
