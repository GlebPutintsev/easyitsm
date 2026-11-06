package ru.easyitsm.sla.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Таймеры SLA в роли worker: находит счётчики, у которых наступила следующая проверка (порог, срок или пересчёт),
 * во всех арендаторах и вычисляет их. Отставание публикуется метрикой {@code easyitsm.sla.timer.lag}
 * (оповещение при 60 с, раздел 12.5 ТЗ). Время порогов и нарушений в событиях точное и от отставания не зависит.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class SlaTimerJob {

    private static final Logger log = LoggerFactory.getLogger(SlaTimerJob.class);
    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final SlaTracker tracker;
    private final AtomicLong lagMs = new AtomicLong();

    SlaTimerJob(JdbcClient jdbc, SlaTracker tracker, ObjectProvider<MeterRegistry> meters) {
        this.jdbc = jdbc;
        this.tracker = tracker;
        meters.ifAvailable(r -> Gauge.builder("easyitsm.sla.timer.lag", lagMs, AtomicLong::get)
                .description("Отставание расчёта таймеров SLA, мс")
                .baseUnit("milliseconds")
                .register(r));
    }

    @Scheduled(fixedDelayString = "${easyitsm.sla.timer-interval:PT10S}", initialDelayString = "PT20S")
    void tick() {
        Instant now = Instant.now();
        int processed;
        do {
            processed = runOnce(now);
            now = Instant.now();
        } while (processed == BATCH);
    }

    /** Один проход: обрабатывает до {@value #BATCH} счётчиков, у которых проверка наступила к {@code now}. */
    int runOnce(Instant now) {
        List<Due> due = jdbc.sql("select tenant_id, id, next_check_at from sla_due_instances(:now, :limit)")
                .param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .param("limit", BATCH)
                .query((rs, n) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getObject("next_check_at", OffsetDateTime.class).toInstant()))
                .list();
        lagMs.set(due.isEmpty() ? 0 : Math.max(0, Duration.between(due.getFirst().at(), now).toMillis()));
        for (Due d : due) {
            try {
                TenantContext.runAs(d.tenantId(), () -> tracker.check(d.id(), now));
            } catch (OptimisticLockingFailureException e) {
                // Счётчик изменили одновременно (событие заявки или другой обработчик): проверка пройдёт снова.
                log.debug("Счётчик SLA {} изменён параллельно, повтор в следующем проходе", d.id());
            } catch (RuntimeException e) {
                log.error("Ошибка расчёта счётчика SLA {}", d.id(), e);
            }
        }
        return due.size();
    }

    private record Due(UUID tenantId, UUID id, Instant at) {
    }
}
