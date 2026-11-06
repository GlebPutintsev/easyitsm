package ru.easyitsm.audit.internal;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/** Роль worker: каждые {@code easyitsm.audit.siem.interval} отправляет недоставленные записи всех арендаторов. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class SiemJob {

    private static final Logger log = LoggerFactory.getLogger(SiemJob.class);
    private static final int TENANTS_PER_TICK = 100;

    private final SiemForwarder forwarder;

    SiemJob(SiemForwarder forwarder) {
        this.forwarder = forwarder;
    }

    @Scheduled(fixedDelayString = "${easyitsm.audit.siem.interval:PT5S}",
            initialDelayString = "${easyitsm.audit.siem.initial-delay:PT15S}")
    void tick() {
        if (!forwarder.settings().configured()) {
            return;
        }
        forwarder.resetLag();
        for (UUID tenant : forwarder.pendingTenants(TENANTS_PER_TICK)) {
            try {
                TenantContext.runAs(tenant, () -> {
                    int sent;
                    do {
                        sent = forwarder.deliverBatch();
                    } while (sent == forwarder.settings().batchSize());
                });
            } catch (RuntimeException e) {
                log.error("Ошибка доставки журнала аудита в SIEM для арендатора {}", tenant, e);
            }
        }
    }
}
