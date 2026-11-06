package ru.easyitsm.problems.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.notifications.api.Notifier;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.problems.events.ProblemResolved;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.events.TicketStatusChanged;

/**
 * Реакция на переходы заявок: решённая проблема решает свои инциденты (PRB-04) и публикует
 * {@link ProblemResolved}; выполненное изменение-исправление отмечается в проблеме.
 * <p>
 * Как в остальных модулях: {@code @TransactionalEventListener} после фиксации, арендатор из события и своя
 * транзакция REQUIRES_NEW — с REQUIRED слушатель присоединился бы к уже зафиксированной транзакции.
 */
@Component
class ProblemEventsListener {

    private final ProblemService service;
    private final TicketQueries tickets;
    private final Notifier notifier;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    ProblemEventsListener(ProblemService service, TicketQueries tickets, Notifier notifier,
                          ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.service = service;
        this.tickets = tickets;
        this.notifier = notifier;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    void on(TicketStatusChanged event) {
        TicketSnapshot t = event.ticket();
        if (t.type() == TicketType.PROBLEM) {
            if (t.statusCategory() == StatusCategory.RESOLVED && event.fromCategory() != StatusCategory.RESOLVED) {
                TenantContext.runAs(event.tenantId(), () -> tx.executeWithoutResult(s -> {
                    int resolved = service.onProblemResolved(t);
                    events.publishEvent(new ProblemResolved(event.tenantId(), event.occurredAt(), t.id(), t.number(),
                            resolved));
                }));
            } else if (t.statusCategory().isActive() && (event.fromCategory() == StatusCategory.RESOLVED
                    || event.fromCategory() == StatusCategory.CLOSED)) {
                TenantContext.runAs(event.tenantId(), () -> tx.executeWithoutResult(s ->
                        service.onProblemReopened(t.id())));
            }
        } else if (t.type() == TicketType.CHANGE && t.statusCategory() == StatusCategory.CLOSED) {
            TenantContext.runAs(event.tenantId(), () -> tx.executeWithoutResult(s -> {
                List<UUID> problems = service.onChangeCompleted(t);
                for (UUID problemId : problems) {
                    tickets.find(problemId).filter(p -> p.assigneeId() != null).ifPresent(p -> notifier.notify(
                            p.assigneeId(), "problem", "Изменение " + t.number() + " выполнено",
                            "Проверьте исправление проблемы " + p.number() + " и решите её.",
                            "/agent/problems/" + p.number(), false));
                }
            }));
        }
    }

}
