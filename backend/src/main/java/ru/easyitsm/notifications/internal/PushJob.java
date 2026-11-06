package ru.easyitsm.notifications.internal;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/** Отправка Web Push в роли worker: записи центра уведомлений с push_state = pending во всех арендаторах. */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class PushJob {

    private static final Logger log = LoggerFactory.getLogger(PushJob.class);

    private final JdbcClient jdbc;
    private final PushService push;

    PushJob(JdbcClient jdbc, PushService push) {
        this.jdbc = jdbc;
        this.push = push;
    }

    @Scheduled(fixedDelayString = "${easyitsm.notifications.push-interval:PT5S}",
            initialDelayString = "${easyitsm.notifications.push-initial-delay:PT20S}")
    void tick() {
        record Due(UUID tenantId, UUID id) {
        }
        List<Due> due = jdbc.sql("select tenant_id, id from notification_push_due(200)")
                .query((rs, n) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)))
                .list();
        for (Due d : due) {
            try {
                TenantContext.runAs(d.tenantId(), () -> push.deliver(d.id()));
            } catch (RuntimeException e) {
                log.warn("Push для уведомления {} не отправлен", d.id(), e);
            }
        }
    }
}
