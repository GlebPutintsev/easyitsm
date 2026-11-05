package ru.easyitsm.sla.internal;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.sla.events.SlaBreached;
import ru.easyitsm.sla.events.SlaWarning;
import ru.easyitsm.tickets.api.EscalationKind;
import ru.easyitsm.tickets.api.TicketCommands;

/**
 * Выполняет действия эскалации из настройки цели (SLA-05, INC-04): {@code escalate:<groupId>} — функциональная
 * эскалация в группу, {@code escalate:hierarchical} — руководителю. Остальные действия ({@code notify:*},
 * {@code set_priority:*}) выполняют модули уведомлений и автоматизации по тем же событиям.
 */
@Component
class SlaActionsExecutor {

    private static final Logger log = LoggerFactory.getLogger(SlaActionsExecutor.class);

    private final ObjectProvider<TicketCommands> tickets;
    private final TransactionTemplate tx;

    SlaActionsExecutor(ObjectProvider<TicketCommands> tickets, TransactionTemplate tx) {
        this.tickets = tickets;
        this.tx = tx;
    }

    @TransactionalEventListener
    void on(SlaWarning e) {
        run(e.tenantId(), e.ticketId(), e.actions(), "SLA: достигнут порог " + e.percent() + "%");
    }

    @TransactionalEventListener
    void on(SlaBreached e) {
        run(e.tenantId(), e.ticketId(), e.actions(), "SLA: нарушен срок (" + e.metric() + ")");
    }

    private void run(UUID tenantId, UUID ticketId, List<String> actions, String reason) {
        TicketCommands commands = tickets.getIfAvailable();
        if (commands == null) {
            return;
        }
        for (String action : actions) {
            if (!action.startsWith("escalate:")) {
                continue;
            }
            String arg = action.substring("escalate:".length());
            TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(s -> {
                if ("hierarchical".equals(arg)) {
                    commands.escalate(ticketId, EscalationKind.HIERARCHICAL, null, reason, null);
                } else {
                    commands.escalate(ticketId, EscalationKind.FUNCTIONAL, UUID.fromString(arg), reason, null);
                }
            }));
            log.info("SLA-эскалация {} по заявке {}", action, ticketId);
        }
    }
}
