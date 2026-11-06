package ru.easyitsm.integrations.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Боты на worker: раз в 5 секунд опрашивает включённые боты всех арендаторов и отправляет очередь сообщений.
 * Входящих запросов из интернета не нужно — подходит для установки за NAT.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class MessengerJob {

    private static final Logger log = LoggerFactory.getLogger(MessengerJob.class);
    private static final int BATCH = 50;

    private final MessengerStore store;
    private final MessengerRunner runner;

    MessengerJob(MessengerStore store, MessengerRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${easyitsm.integrations.messenger-interval:PT5S}",
            initialDelayString = "${easyitsm.integrations.messenger-initial-delay:PT20S}")
    void run() {
        for (SourceStore.TokenMatch bot : store.enabledEverywhere()) {
            try {
                TenantContext.runAs(bot.tenantId(), () -> runner.poll(bot.sourceId()));
            } catch (RuntimeException e) {
                log.warn("Опрос бота {} не выполнен", bot.sourceId(), e);
            }
        }
        for (SourceStore.TokenMatch due : store.due(BATCH)) {
            try {
                TenantContext.runAs(due.tenantId(), () -> runner.send(due.sourceId()));
            } catch (RuntimeException e) {
                log.warn("Сообщение бота {} не отправлено", due.sourceId(), e);
            }
        }
    }
}
