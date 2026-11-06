package ru.easyitsm.notifications.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.easyitsm.catalog.events.ApprovalDecided;
import ru.easyitsm.catalog.events.ApprovalRequested;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.identity.events.PasswordResetRequested;
import ru.easyitsm.sla.events.SlaBreached;
import ru.easyitsm.sla.events.SlaWarning;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * Уведомления по событиям SLA (SLA-05), согласований (5.4) и входа (сброс пароля, 10.3).
 * <p>
 * SLA: действия {@code notify:assignee}, {@code notify:group_lead}, {@code notify:group} из настройки цели;
 * без действий — исполнителю, а если его нет — руководителю группы. Предупреждение — центр и push,
 * нарушение — ещё и письмо.
 */
@Component
class ProcessNotifications {

    private final NotificationPlanner planner;
    private final TicketQueries tickets;
    private final UserDirectory users;

    ProcessNotifications(NotificationPlanner planner, TicketQueries tickets, UserDirectory users) {
        this.planner = planner;
        this.tickets = tickets;
        this.users = users;
    }

    @TransactionalEventListener
    void on(SlaWarning e) {
        planner.inTenant(e.tenantId(), plan -> tickets.find(e.ticketId()).ifPresent(t -> {
            for (UUID id : slaRecipients(t, e.actions())) {
                UserSummary u = users.findUser(id).orElse(null);
                String tz = u == null ? null : u.timezone();
                boolean en = u != null && "en".equals(Templates.locale(u.locale()));
                plan.support(List.of(id), t, "sla_warning", Map.of("sla.percent", String.valueOf(e.percent()),
                        "sla.metric", NotificationModel.metric(e.metric(), en),
                        "sla.due", NotificationModel.time(e.dueAt(), tz)), false, true);
            }
        }));
    }

    @TransactionalEventListener
    void on(SlaBreached e) {
        planner.inTenant(e.tenantId(), plan -> tickets.find(e.ticketId()).ifPresent(t -> {
            for (UUID id : slaRecipients(t, e.actions())) {
                boolean en = users.findUser(id).map(u -> "en".equals(Templates.locale(u.locale()))).orElse(false);
                plan.support(List.of(id), t, "sla_breached",
                        Map.of("sla.metric", NotificationModel.metric(e.metric(), en)), true, true);
            }
        }));
    }

    private List<UUID> slaRecipients(TicketSnapshot t, List<String> actions) {
        List<UUID> out = new ArrayList<>();
        boolean any = false;
        for (String a : actions == null ? List.<String>of() : actions) {
            switch (a) {
                case "notify:assignee" -> {
                    any = true;
                    if (t.assigneeId() != null) {
                        out.add(t.assigneeId());
                    }
                }
                case "notify:group_lead" -> {
                    any = true;
                    lead(t).ifPresent(out::add);
                }
                case "notify:group" -> {
                    any = true;
                    if (t.groupId() != null) {
                        out.addAll(users.activeMembersOf(t.groupId()));
                    }
                }
                default -> {
                }
            }
        }
        if (!any || out.isEmpty()) {
            if (t.assigneeId() != null) {
                out.add(t.assigneeId());
            } else {
                lead(t).ifPresent(out::add);
            }
        }
        return out.stream().distinct().toList();
    }

    private Optional<UUID> lead(TicketSnapshot t) {
        return t.groupId() == null ? Optional.empty() : users.leaderOf(t.groupId());
    }

    @TransactionalEventListener
    void on(ApprovalRequested e) {
        planner.inTenant(e.tenantId(), plan -> {
            TicketSnapshot t = tickets.find(e.ticketId()).orElse(null);
            String tz = users.findUser(e.approverId()).map(UserSummary::timezone).orElse(null);
            Map<String, String> model = new HashMap<>();
            if (t != null) {
                model.putAll(NotificationModel.ticket(t, "ru", ""));
            } else {
                model.put("ticket.number", e.ticketNumber());
                model.put("ticket.title", e.title());
            }
            model.put("approval.step", e.stepName() == null ? "" : e.stepName());
            model.put("approval.due", NotificationModel.time(e.dueAt(), tz));
            model.put("approval.link", planner.url("/portal/approvals"));
            model.put("ticket.link", planner.url("/portal/approvals"));
            plan.user(e.approverId(), "approval_requested", model, t, "/portal/approvals",
                    new Dispatcher.Channels(true, true, true));
        });
    }

    @TransactionalEventListener
    void on(ApprovalDecided e) {
        planner.inTenant(e.tenantId(), plan -> tickets.find(e.ticketId()).ifPresent(t -> {
            plan.exclude(e.decidedBy());
            boolean approved = "approved".equals(e.outcome());
            for (UUID id : TicketNotifications.requesterSide(t)) {
                boolean en = users.findUser(id).map(u -> "en".equals(Templates.locale(u.locale()))).orElse(false);
                String outcome = approved ? (en ? "approved" : "согласован") : (en ? "rejected" : "отклонён");
                plan.requesterOne(id, t, "approval_decided",
                        Map.of("approval.outcome", outcome, "reason", e.reason() == null ? "" : e.reason()));
            }
        }));
    }

    @TransactionalEventListener
    void on(PasswordResetRequested e) {
        planner.inTenant(e.tenantId(), plan -> {
            UserSummary u = users.findUser(e.userId()).orElse(null);
            String locale = Templates.locale(u == null ? null : u.locale());
            Map<String, String> model = Map.of("reset.link", e.resetUrl(),
                    "reset.expires", NotificationModel.time(e.expiresAt(), u == null ? null : u.timezone()),
                    "recipient.name", u == null ? "" : u.displayName());
            plan.address(new Dispatcher.Recipient(e.userId(), e.email(), u == null ? null : u.displayName(), locale,
                    u == null ? null : u.timezone()), "password_reset", model);
        });
    }
}
