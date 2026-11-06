package ru.easyitsm.search.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Плановые задачи поиска в роли worker: очередь индексации каждую секунду и ночная сверка индекса с БД. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class SearchJobs {

    private static final Logger log = LoggerFactory.getLogger(SearchJobs.class);

    private final SearchIndexer indexer;

    SearchJobs(SearchIndexer indexer) {
        this.indexer = indexer;
    }

    @Scheduled(fixedDelayString = "${easyitsm.search.indexing.interval:PT1S}", initialDelayString = "PT10S")
    void drain() {
        try {
            // Пока очередь отдаёт полные пачки, разбираем её без паузы: первичная загрузка идёт быстрее.
            int sent;
            do {
                sent = indexer.runOnce();
            } while (sent > 0 && sent >= 100);
        } catch (RuntimeException e) {
            log.warn("Индексация: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${easyitsm.search.indexing.reconcile:0 30 3 * * *}")
    void reconcile() {
        try {
            indexer.reconcileAll();
        } catch (RuntimeException e) {
            log.warn("Сверка поискового индекса: {}", e.getMessage());
        }
    }
}
