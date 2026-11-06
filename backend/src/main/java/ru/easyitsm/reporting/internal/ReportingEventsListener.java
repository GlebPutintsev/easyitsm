package ru.easyitsm.reporting.internal;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.sla.events.SlaBreached;
import ru.easyitsm.surveys.events.SurveySubmitted;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.events.TicketEvent;
import ru.easyitsm.tickets.events.TicketStatusChanged;

/**
 * Наполняет витрину отчётов из событий модулей tickets, sla и surveys (outbox).
 * <p>
 * Как в модуле SLA: не {@code @ApplicationModuleListener}, потому что его транзакция открывается до того, как
 * известен арендатор события. Здесь арендатор ставится из события, затем {@link FactStore} открывает свою
 * транзакцию. При ошибке публикация остаётся незавершённой и повторяется при перезапуске.
 */
@Component
class ReportingEventsListener {

    private final FactStore facts;

    ReportingEventsListener(FactStore facts) {
        this.facts = facts;
    }

    @TransactionalEventListener
    void on(TicketEvent event) {
        TenantContext.runAs(event.tenantId(),
                () -> facts.applyTicket(event.ticket(), event.occurredAt(), reopened(event)));
    }

    @TransactionalEventListener
    void on(SlaBreached event) {
        TenantContext.runAs(event.tenantId(), () -> facts.applyBreach(event.instanceId(), event.ticketId(),
                event.kind(), event.metric(), event.priority(), event.breachedAt()));
    }

    @TransactionalEventListener
    void on(SurveySubmitted event) {
        TenantContext.runAs(event.tenantId(), () -> facts.applySurvey(event.ticketId(), event.score(), Instant.now()));
    }

    /** Возврат решённой заявки в работу (повторное открытие). */
    static boolean reopened(TicketEvent event) {
        if (!(event instanceof TicketStatusChanged sc)) {
            return false;
        }
        if ("reopened".equals(sc.reasonCode())) {
            return true;
        }
        return sc.fromCategory() == StatusCategory.RESOLVED && sc.ticket().statusCategory().isActive();
    }
}
