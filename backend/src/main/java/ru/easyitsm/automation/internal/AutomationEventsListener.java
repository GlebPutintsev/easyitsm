package ru.easyitsm.automation.internal;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.sla.events.SlaBreached;
import ru.easyitsm.sla.events.SlaWarning;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.events.TicketAssignmentChanged;
import ru.easyitsm.tickets.events.TicketCommentAdded;
import ru.easyitsm.tickets.events.TicketCreated;
import ru.easyitsm.tickets.events.TicketEscalated;
import ru.easyitsm.tickets.events.TicketEvent;
import ru.easyitsm.tickets.events.TicketPriorityChanged;
import ru.easyitsm.tickets.events.TicketStatusChanged;
import ru.easyitsm.tickets.events.TicketUpdated;

/**
 * Переводит события заявок и SLA в {@link AutomationEngine.Occurrence}.
 * <p>
 * Не {@code @ApplicationModuleListener}: его транзакция открывается до того, как известен арендатор события
 * (см. {@code SlaTicketEventsListener}). Здесь арендатор ставится из события, транзакции открывает движок.
 * Слушатель вызывается синхронно после фиксации транзакции события — на этом держится {@link RunChain}.
 */
@Component
class AutomationEventsListener {

    private final AutomationEngine engine;
    private final TicketQueries tickets;
    private final TransactionTemplate readTx;

    AutomationEventsListener(AutomationEngine engine, TicketQueries tickets, PlatformTransactionManager tm) {
        this.engine = engine;
        this.tickets = tickets;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    @TransactionalEventListener
    void on(TicketEvent event) {
        occurrence(event).ifPresent(o -> TenantContext.runAs(event.tenantId(), () -> engine.handle(o)));
    }

    @TransactionalEventListener
    void on(SlaWarning e) {
        TenantContext.runAs(e.tenantId(), () -> ticket(e.ticketId()).ifPresent(t -> engine.handle(
                new AutomationEngine.Occurrence(Trigger.SLA_WARNING, t, Facts.of(t).put("slaKind", e.kind())
                        .put("slaMetric", e.metric()).put("slaPercent", e.percent()),
                        "sla_warning:" + e.instanceId() + ":" + e.percent(), e.actions()))));
    }

    @TransactionalEventListener
    void on(SlaBreached e) {
        TenantContext.runAs(e.tenantId(), () -> ticket(e.ticketId()).ifPresent(t -> engine.handle(
                new AutomationEngine.Occurrence(Trigger.SLA_BREACHED, t, Facts.of(t).put("slaKind", e.kind())
                        .put("slaMetric", e.metric()), "sla_breached:" + e.instanceId(), e.actions()))));
    }

    private Optional<TicketSnapshot> ticket(java.util.UUID id) {
        return readTx.execute(status -> tickets.find(id));
    }

    static Optional<AutomationEngine.Occurrence> occurrence(TicketEvent event) {
        TicketSnapshot t = event.ticket();
        Facts f = Facts.of(t);
        String key = ":" + t.id() + ":" + t.version();
        Trigger trigger;
        switch (event) {
            case TicketCreated e -> trigger = Trigger.TICKET_CREATED;
            case TicketStatusChanged e -> {
                trigger = Trigger.STATUS_CHANGED;
                f.put("fromStatusCode", e.fromStatusCode())
                        .put("fromStatusCategory", e.fromCategory() == null ? null : e.fromCategory().code())
                        .put("transitionTrigger", e.trigger());
            }
            case TicketPriorityChanged e -> {
                trigger = Trigger.PRIORITY_CHANGED;
                f.put("fromPriority", e.fromPriority() == null ? null : e.fromPriority().code());
            }
            case TicketAssignmentChanged e -> {
                trigger = Trigger.ASSIGNMENT_CHANGED;
                f.put("fromGroupId", e.fromGroupId()).put("fromAssigneeId", e.fromAssigneeId());
            }
            case TicketUpdated e -> {
                trigger = Trigger.TICKET_UPDATED;
                f.put("changedFields", e.fields() == null ? List.of() : List.copyOf(e.fields()));
            }
            case TicketCommentAdded e -> {
                trigger = Trigger.COMMENT_ADDED;
                f.put("visibility", e.visibility() == null ? null : e.visibility().code())
                        .put("fromRequester", e.fromRequester());
                key = ":" + e.activityId();
            }
            case TicketEscalated e -> {
                trigger = Trigger.ESCALATED;
                f.put("escalationKind", e.kind() == null ? null : e.kind().code()).put("escalationLevel", e.level());
            }
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(new AutomationEngine.Occurrence(trigger, t, f, trigger.code() + key, List.of()));
    }
}
