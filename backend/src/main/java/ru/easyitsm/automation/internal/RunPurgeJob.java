package ru.easyitsm.automation.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Удаляет журнал запусков старше {@code easyitsm.automation.keep-runs-days} во всех арендаторах. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class RunPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RunPurgeJob.class);

    private final RunLog runs;
    private final AutomationProperties props;

    RunPurgeJob(RunLog runs, AutomationProperties props) {
        this.runs = runs;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    void purge() {
        int deleted = runs.purge(props.keepRunsDays());
        if (deleted > 0) {
            log.info("Удалено записей журнала автоматизации: {}", deleted);
        }
    }
}
