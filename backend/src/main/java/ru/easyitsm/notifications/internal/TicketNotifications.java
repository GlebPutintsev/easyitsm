package ru.easyitsm.notifications.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketComment;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.Visibility;
import ru.easyitsm.tickets.events.TicketAssignmentChanged;
import ru.easyitsm.tickets.events.TicketCommentAdded;
import ru.easyitsm.tickets.events.TicketCreated;
import ru.easyitsm.tickets.events.TicketEvent;
import ru.easyitsm.tickets.events.TicketStatusChanged;

/**
 * Правила уведомлений по событиям заявок. Каждый получатель получает не больше одного уведомления на событие,
 * автор действия — никакого (кроме письма-подтверждения о регистрации заявки).
 * <ul>
 *   <li>создание — заявителю письмо о регистрации; исполнителю — «назначена»; без исполнителя — группе в центр;</li>
 *   <li>назначение — новому исполнителю, а если исполнителя нет — участникам новой группы;</li>
 *   <li>публичный ответ поддержки — заявителю с текстом ответа; ответ заявителя — исполнителю (или группе);
 *       внутренняя заметка — исполнителю; упоминание — упомянутым; наблюдателям — в центр уведомлений;</li>
 *   <li>статус: ожидание ответа заявителя, решение, отмена — заявителю; возврат в работу — исполнителю.</li>
 * </ul>
 */
@Component
class TicketNotifications {

    private static final Logger log = LoggerFactory.getLogger(TicketNotifications.class);

    private final NotificationPlanner planner;
    private final TicketQueries tickets;

    TicketNotifications(NotificationPlanner planner, TicketQueries tickets) {
        this.planner = planner;
        this.tickets = tickets;
    }

    @TransactionalEventListener
    void on(TicketEvent event) {
        planner.inTenant(event.tenantId(), plan -> handle(event, plan));
    }

    private void handle(TicketEvent event, NotificationPlanner.Plan plan) {
        TicketSnapshot t = event.ticket();
        UUID actor = event.actorId();
        plan.exclude(actor);
        switch (event) {
            case TicketCreated e -> {
                plan.allowSelfEmail("ticket_created");
                plan.requester(t, "ticket_created", Map.of(), true, false);
                if (t.assigneeId() != null) {
                    plan.support(List.of(t.assigneeId()), t, "ticket_assigned", Map.of(), true, true);
                } else if (t.groupId() != null) {
                    plan.group(t.groupId(), t, "ticket_queued");
                }
            }
            case TicketAssignmentChanged e -> {
                if (t.assigneeId() != null && !t.assigneeId().equals(e.fromAssigneeId())) {
                    plan.support(List.of(t.assigneeId()), t, "ticket_assigned", Map.of(), true, true);
                } else if (t.assigneeId() == null && t.groupId() != null && !t.groupId().equals(e.fromGroupId())) {
                    plan.group(t.groupId(), t, "ticket_queued");
                }
            }
            case TicketCommentAdded e -> comment(e, t, plan);
            case TicketStatusChanged e -> status(e, t, plan);
            default -> {
                // остальные события (приоритет, поля, наблюдатели, эскалация) — без уведомлений в MVP
            }
        }
    }

    private void comment(TicketCommentAdded e, TicketSnapshot t, NotificationPlanner.Plan plan) {
        String body = tickets.findComment(t.id(), e.activityId()).map(TicketComment::body).orElse("");
        Map<String, String> extra = Map.of("comment.body", body);
        if (!e.mentionedUserIds().isEmpty()) {
            // Упомянуть во внутренней заметке можно только поддержку: ссылка ведёт в рабочее место.
            plan.support(e.mentionedUserIds(), t, "ticket_mentioned", extra, true, true);
        }
        if (e.visibility() == Visibility.PUBLIC) {
            if (e.fromRequester()) {
                if (t.assigneeId() != null) {
                    plan.support(List.of(t.assigneeId()), t, "ticket_requester_reply", extra, true, true);
                } else if (t.groupId() != null) {
                    plan.groupMembers(t.groupId(), t, "ticket_requester_reply", extra);
                }
            } else {
                plan.requester(t, "ticket_reply", extra, true, true);
            }
            plan.support(tickets.watchers(t.id()), t, "ticket_watch", extra, false, false);
        } else if (t.assigneeId() != null) {
            plan.support(List.of(t.assigneeId()), t, "ticket_note", extra, false, true);
        }
    }

    private void status(TicketStatusChanged e, TicketSnapshot t, NotificationPlanner.Plan plan) {
        StatusCategory to = t.statusCategory();
        Map<String, String> reason = Map.of("reason", e.reasonText() == null ? "" : e.reasonText());
        if (to == StatusCategory.PENDING && "requester".equals(e.reasonCode())) {
            plan.requester(t, "ticket_pending", reason, true, true);
        } else if (to == StatusCategory.RESOLVED) {
            plan.requester(t, "ticket_resolved", reason, true, true);
        } else if (to == StatusCategory.CANCELLED) {
            plan.requester(t, "ticket_cancelled", reason, true, false);
        } else if (e.fromCategory() == StatusCategory.RESOLVED && !to.isFinal() && t.assigneeId() != null) {
            plan.support(List.of(t.assigneeId()), t, "ticket_reopened", reason, true, true);
        } else {
            return;
        }
        if (to == StatusCategory.RESOLVED || to == StatusCategory.CANCELLED) {
            plan.support(tickets.watchers(t.id()), t, "ticket_watch", Map.of(), false, false);
        }
    }

    static Set<UUID> ids(Collection<UUID> ids) {
        Set<UUID> out = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    static List<UUID> requesterSide(TicketSnapshot t) {
        List<UUID> out = new ArrayList<>();
        out.add(t.requesterId());
        if (t.createdForId() != null && !Objects.equals(t.createdForId(), t.requesterId())) {
            out.add(t.createdForId());
        }
        return out;
    }

    static Logger log() {
        return log;
    }
}
