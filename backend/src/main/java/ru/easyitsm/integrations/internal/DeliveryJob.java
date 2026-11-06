package ru.easyitsm.integrations.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Доставка вебхуков на worker: раз в 10 секунд берёт доставки с наступившим сроком во всех арендаторах.
 * Строка доставки блокируется {@code FOR UPDATE SKIP LOCKED}, поэтому несколько подов не отправят её дважды.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class DeliveryJob {

    private static final Logger log = LoggerFactory.getLogger(DeliveryJob.class);
    private static final int BATCH = 50;

    private final DeliveryStore deliveries;
    private final WebhookService service;

    DeliveryJob(DeliveryStore deliveries, WebhookService service) {
        this.deliveries = deliveries;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${easyitsm.integrations.delivery-interval:PT10S}",
            initialDelayString = "${easyitsm.integrations.delivery-initial-delay:PT30S}")
    void run() {
        for (SourceStore.TokenMatch due : deliveries.due(BATCH)) {
            try {
                TenantContext.runAs(due.tenantId(), () -> service.deliver(due.sourceId()));
            } catch (RuntimeException e) {
                log.warn("Доставка вебхука {} не выполнена", due.sourceId(), e);
            }
        }
    }
}
