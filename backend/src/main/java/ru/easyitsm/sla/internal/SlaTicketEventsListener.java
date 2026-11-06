package ru.easyitsm.sla.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
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
 * <p>
 * Транзакция всегда новая ({@code REQUIRES_NEW}): слушатель вызывается после фиксации транзакции события, когда её
 * соединение ещё привязано к потоку. С {@code REQUIRED} трекер присоединился бы к уже завершённой транзакции,
 * блокировка {@code sla_ticket} держалась бы до её очистки, и цепочка правил автоматизации на той же заявке
 * ждала бы саму себя.
 */
@Component
class SlaTicketEventsListener {

    private final SlaTracker tracker;
    private final TransactionTemplate tx;

    SlaTicketEventsListener(SlaTracker tracker, PlatformTransactionManager tm) {
        this.tracker = tracker;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    void on(TicketEvent event) {
        TenantContext.runAs(event.tenantId(), () -> tx.executeWithoutResult(s -> tracker.apply(facts(event))));
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
