package ru.easyitsm.tickets.internal;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Таймеры заявок по всем арендаторам: автозакрытие решённых через N рабочих дней (INC-07) и возврат из
 * ожидания по сроку. Список берётся функцией SECURITY DEFINER, обработка — в арендаторе заявки.
 */
@Component
@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)
class TicketTimersJob {

    private static final Logger log = LoggerFactory.getLogger(TicketTimersJob.class);
    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final TicketService tickets;

    TicketTimersJob(JdbcClient jdbc, TicketService tickets) {
        this.jdbc = jdbc;
        this.tickets = tickets;
    }

    private record Due(UUID tenantId, UUID ticketId, String timer) {
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    void run() {
        runOnce();
    }

    /** @return число сработавших таймеров */
    int runOnce() {
        var due = jdbc.sql("select tenant_id, ticket_id, timer from tickets_due_timers(:before, :rows)")
                .param("before", Sql.ts(Sql.now().minus(Duration.ofDays(1)))).param("rows", BATCH)
                .query((rs, n) -> new Due(Sql.uuid(rs, "tenant_id"), Sql.uuid(rs, "ticket_id"), rs.getString("timer")))
                .list();
        int fired = 0;
        for (Due d : due) {
            try {
                Boolean ok = TenantContext.callAs(d.tenantId(), () -> tickets.fireTimer(d.ticketId(), d.timer()));
                if (Boolean.TRUE.equals(ok)) {
                    fired++;
                }
            } catch (Exception e) {
                log.warn("Таймер {} заявки {} не сработал: {}", d.timer(), d.ticketId(), e.getMessage());
            }
        }
        return fired;
    }
}
