package ru.easyitsm.assets.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Напоминания о продлении договоров (AST-04) и окончании лицензий (AST-03) в роли worker. Очередь по всем
 * арендаторам отдаёт функция {@code assets_renewal_queue}, каждая запись обрабатывается под своим арендатором.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class RenewalJob {

    private static final Logger log = LoggerFactory.getLogger(RenewalJob.class);
    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final ContractService contracts;
    private final SoftwareLicenseService licenses;
    private final Clock clock;

    RenewalJob(JdbcClient jdbc, ContractService contracts, SoftwareLicenseService licenses, Optional<Clock> clock) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.licenses = licenses;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    private record Item(UUID tenantId, UUID id, String kind) {
    }

    @Scheduled(fixedDelayString = "${easyitsm.assets.renewal-check-interval:PT1H}", initialDelayString = "PT3M")
    void run() {
        runOnce(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** Один проход на дату {@code today}. @return сколько напоминаний отправлено */
    int runOnce(LocalDate today) {
        List<Item> items = jdbc.sql("select tenant_id, id, kind from assets_renewal_queue(:today, :max)")
                .param("today", today).param("max", BATCH)
                .query((rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .list();
        int done = 0;
        for (Item item : items) {
            try {
                TenantContext.runAs(item.tenantId(), () -> {
                    if ("contract".equals(item.kind())) {
                        contracts.remindBySchedule(item.id(), today);
                    } else {
                        licenses.remindBySchedule(item.id(), today);
                    }
                });
                done++;
            } catch (RuntimeException e) {
                log.warn("Напоминание о продлении {} {} не отправлено: {}", item.kind(), item.id(),
                        e.getClass().getSimpleName());
            }
        }
        if (done > 0) {
            log.info("Отправлено напоминаний о продлении договоров и лицензий: {}", done);
        }
        return done;
    }
}
