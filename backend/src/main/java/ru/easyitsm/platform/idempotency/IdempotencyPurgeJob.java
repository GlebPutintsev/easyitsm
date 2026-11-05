package ru.easyitsm.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Удаляет записи идемпотентности старше 24 часов во всех арендаторах. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);
    private final JdbcClient jdbc;

    IdempotencyPurgeJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void purge() {
        Integer deleted = jdbc.sql("select platform_purge_idempotency()").query(Integer.class).single();
        if (deleted != null && deleted > 0) {
            log.info("Удалено устаревших записей идемпотентности: {}", deleted);
        }
    }
}
