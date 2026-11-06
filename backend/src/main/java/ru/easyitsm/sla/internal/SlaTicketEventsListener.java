package ru.easyitsm.sla.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.events.TicketEvent;
import ru.easyitsm.tickets.events.TicketStatusChanged;

/**
 * Слушает события модуля tickets через outbox и передаёт снимок заявки в {@link SlaTracker}.
 * <p>
 * Не {@code @ApplicationModuleListener}: его транзакция открывается до того, как известен арендатор события,
 * и соединение получило бы пустой {@code app.tenant_id}. Здесь арендатор ставится из события, затем открывается
 * транзакция трекера. При ошибке публикация остаётся незавершённой и повторяется при перезапуске.
 */
@Component
class SlaTicketEventsListener {

    private final SlaTracker tracker;

    SlaTicketEventsListener(SlaTracker tracker) {
        this.tracker = tracker;
    }

    @TransactionalEventListener
    void on(TicketEvent event) {
        TenantContext.runAs(event.tenantId(), () -> tracker.apply(facts(event)));
    }

    static TicketFacts facts(TicketEvent event) {
        TicketSnapshot t = event.ticket();
        String pauseReason = event instanceof TicketStatusChanged sc
                ? (sc.reasonText() != null && !sc.reasonText().isBlank()
                        ? sc.reasonCode() == null ? sc.reasonText() : sc.reasonCode() + ": " + sc.reasonText()
                        : sc.reasonCode())
                : null;
        return new TicketFacts(
                t.id(),
                event.occurredAt(),
                t.createdAt(),
                t.type() == null ? null : t.type().code(),
                t.priority() == null ? null : t.priority().code(),
                t.serviceId(),
                t.organizationId(),
                t.groupId(),
                t.statusCode(),
                t.statusCategory() == null ? null : t.statusCategory().code(),
                event instanceof TicketStatusChanged ? event.occurredAt() : null,
                pauseReason,
                t.firstResponseAt(),
                t.resolvedAt());
    }
}
