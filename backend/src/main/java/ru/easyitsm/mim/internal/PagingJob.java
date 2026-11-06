package ru.easyitsm.mim.internal;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/** Эскалация оповещений без подтверждения (MIM-03) в роли worker: по всем арендаторам, раз в 30 секунд. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class PagingJob {

    private static final Logger log = LoggerFactory.getLogger(PagingJob.class);
    private static final int BATCH = 200;

    private final MimStore store;
    private final MimService service;

    PagingJob(MimStore store, MimService service) {
        this.store = store;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${easyitsm.mim.paging-interval:PT30S}", initialDelayString = "PT30S")
    void tick() {
        runOnce(Instant.now());
    }

    int runOnce(Instant now) {
        List<MimStore.Due> due = store.duePages(now, BATCH);
        for (MimStore.Due d : due) {
            try {
                TenantContext.runAs(d.tenantId(), () -> service.advancePage(d.id(), now));
            } catch (RuntimeException e) {
                log.error("Ошибка эскалации оповещения {}", d.id(), e);
            }
        }
        return due.size();
    }
}
