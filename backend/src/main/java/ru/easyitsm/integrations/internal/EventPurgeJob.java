package ru.easyitsm.integrations.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Удаляет журнал событий мониторинга старше {@code easyitsm.integrations.keep-events-days} во всех арендаторах. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class EventPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EventPurgeJob.class);

    private final EventLog events;
    private final IntegrationsProperties props;

    EventPurgeJob(EventLog events, IntegrationsProperties props) {
        this.events = events;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT15M")
    void purge() {
        int deleted = events.purge(props.keepEventsDays());
        if (deleted > 0) {
            log.info("Удалено записей журнала мониторинга: {}", deleted);
        }
    }
}
