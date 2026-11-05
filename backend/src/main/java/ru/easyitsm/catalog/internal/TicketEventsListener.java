package ru.easyitsm.catalog.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.events.TicketStatusChanged;

/** Отменённая заявка закрывает свой маршрут согласования: согласующим больше нечего решать. */
@Component
class TicketEventsListener {

    private final ApprovalService approvals;

    TicketEventsListener(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @ApplicationModuleListener
    void on(TicketStatusChanged event) {
        if (event.ticket().statusCategory() == StatusCategory.CANCELLED) {
            TenantContext.runAs(event.tenantId(), () -> approvals.cancelForTicket(event.ticketId()));
        }
    }
}
