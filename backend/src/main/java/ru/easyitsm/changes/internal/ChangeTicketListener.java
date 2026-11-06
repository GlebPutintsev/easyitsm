package ru.easyitsm.changes.internal;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.events.TicketEvent;

/**
 * Заявки типа change: заводит черновик изменения, если заявку создали в обход модуля, и держит копию полей
 * заявки (номер, тема, автор, группа, исполнитель) для списков и календаря.
 * <p>
 * Не {@code @ApplicationModuleListener}: арендатор ставится из события, транзакция — новая (REQUIRES_NEW), иначе
 * слушатель присоединится к уже зафиксированной транзакции события.
 */
@Component
class ChangeTicketListener {

    private final ChangeService service;
    private final TransactionTemplate tx;

    ChangeTicketListener(ChangeService service, PlatformTransactionManager tm) {
        this.service = service;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    void on(TicketEvent event) {
        if (event.ticket().type() != TicketType.CHANGE) {
            return;
        }
        TenantContext.runAs(event.tenantId(), () -> tx.executeWithoutResult(s -> service.onTicket(event.ticket())));
    }
}
