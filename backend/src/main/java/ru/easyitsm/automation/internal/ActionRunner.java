package ru.easyitsm.automation.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.tickets.api.EscalationKind;
import ru.easyitsm.tickets.api.Priority;
import ru.easyitsm.tickets.api.TicketChange;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;
import ru.easyitsm.tickets.api.TicketTransition;
import ru.easyitsm.tickets.api.Visibility;

/**
 * Выполняет действия правила через {@link TicketCommands} от имени системы и описывает их для проверки правила
 * без изменений. Перед каждым действием заявка читается заново: предыдущее действие могло её изменить.
 */
@Component
class ActionRunner {

    /** Триггер перехода в событии {@code TicketStatusChanged}, когда статус сменило правило. */
    static final String TRIGGER = "automation";

    private final TicketCommands commands;
    private final TicketQueries tickets;
    private final UserDirectory directory;

    ActionRunner(TicketCommands commands, TicketQueries tickets, UserDirectory directory) {
        this.commands = commands;
        this.tickets = tickets;
        this.directory = directory;
    }

    /** Выполняет действия по порядку. Ошибка любого — {@link ActionFailed}, вызывающий откатывает транзакцию. */
    List<Map<String, Object>> apply(String ruleName, List<Action> actions, UUID ticketId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            try {
                TicketSnapshot before = current(ticketId);
                String detail = execute(ruleName, a, before);
                TicketSnapshot after = current(ticketId);
                boolean changed = after.version() != before.version() || detail != null;
                results.add(result(a.type(), changed ? "applied" : "unchanged", detail));
            } catch (ActionFailed e) {
                throw e;
            } catch (ApiException e) {
                throw new ActionFailed(i, a.type(), e.code(), e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new ActionFailed(i, a.type(), "internal_error", "Внутренняя ошибка при выполнении действия", e);
            }
        }
        return results;
    }

    /** @return пояснение для журнала, если действие что-то добавило без смены версии заявки (запись, подписка) */
    private String execute(String ruleName, Action a, TicketSnapshot t) {
        String reason = "Правило автоматизации «" + ruleName + "»";
        switch (a.type()) {
            case "assign" -> {
                requireAssignment(a, t);
                commands.change(t.id(), new TicketChange(null, null, null, a.groupId(), a.assigneeId(), false, reason));
                return null;
            }
            case "set_priority" -> {
                Priority p = Priority.fromCode(a.priority());
                if (p != t.priority()) {
                    commands.change(t.id(), new TicketChange(p, null, null, null, null, false, reason));
                }
                return null;
            }
            case "set_status" -> {
                if (!a.status().equals(t.statusCode())) {
                    commands.transition(t.id(), new TicketTransition(a.status(), render(a.body(), t, ruleName),
                            a.reasonCode(), a.resolutionCode(), null), TRIGGER);
                }
                return null;
            }
            case "add_comment" -> {
                Visibility v = "public".equals(a.visibility()) ? Visibility.PUBLIC : Visibility.INTERNAL;
                commands.addComment(t.id(), null, render(a.body(), t, ruleName), v);
                return v.code();
            }
            case "add_watcher" -> {
                return commands.addWatcher(t.id(), a.userId(), "added") ? "added" : null;
            }
            case "escalate" -> {
                commands.escalate(t.id(), EscalationKind.fromCode(a.kind()), a.groupId(),
                        render(a.reason(), t, ruleName), null);
                return null;
            }
            default -> throw ApiException.badRequest("invalid_rule", "Неизвестное действие " + a.type());
        }
    }

    /**
     * Назначение проверяется в момент запуска: группу могли удалить, исполнителя — вывести из группы после
     * сохранения правила. Модуль заявок не сверяет назначения, которые делает система.
     */
    private void requireAssignment(Action a, TicketSnapshot t) {
        UUID group = a.groupId() != null ? a.groupId() : t.groupId();
        if (a.groupId() != null && directory.findGroup(a.groupId()).isEmpty()) {
            throw ApiException.badRequest("invalid_assignment", "Группа из правила не найдена");
        }
        if (a.assigneeId() != null && group != null && !directory.isMember(group, a.assigneeId())) {
            throw ApiException.badRequest("invalid_assignment", "Исполнитель из правила не состоит в группе заявки");
        }
    }

    /** Что сделает каждое действие с заявкой в её текущем виде (проверка правила, ничего не меняет). */
    List<Map<String, Object>> plan(String ruleName, List<Action> actions, TicketSnapshot t) {
        List<Map<String, Object>> plan = new ArrayList<>();
        for (Action a : actions) {
            String outcome = "change";
            String detail = null;
            switch (a.type()) {
                case "assign" -> {
                    try {
                        requireAssignment(a, t);
                        boolean sameGroup = a.groupId() == null || a.groupId().equals(t.groupId());
                        boolean sameAssignee = a.assigneeId() == null || a.assigneeId().equals(t.assigneeId());
                        outcome = sameGroup && sameAssignee ? "no_change" : "change";
                    } catch (ApiException e) {
                        outcome = "error";
                        detail = e.getMessage();
                    }
                }
                case "set_priority" -> outcome = Objects.equals(a.priority(), t.priority() == null ? null
                        : t.priority().name()) ? "no_change" : "change";
                case "set_status" -> outcome = a.status().equals(t.statusCode()) ? "no_change" : "change";
                case "add_comment" -> detail = render(a.body(), t, ruleName);
                case "escalate" -> detail = render(a.reason(), t, ruleName);
                default -> {
                }
            }
            boolean keepsWorking = "add_watcher".equals(a.type())
                    || ("add_comment".equals(a.type()) && !"public".equals(a.visibility()));
            if (t.statusCategory() != null && t.statusCategory().isFinal() && !keepsWorking) {
                outcome = "error";
                detail = "Заявка закрыта";
            }
            plan.add(result(a.type(), outcome, detail));
        }
        return plan;
    }

    static String render(String template, TicketSnapshot t, String ruleName) {
        if (template == null) {
            return null;
        }
        return template
                .replace("{{number}}", nz(t.number()))
                .replace("{{title}}", nz(t.title()))
                .replace("{{priority}}", t.priority() == null ? "" : t.priority().name())
                .replace("{{status}}", nz(t.statusCode()))
                .replace("{{rule}}", nz(ruleName));
    }

    private TicketSnapshot current(UUID ticketId) {
        return tickets.find(ticketId).orElseThrow(() -> ApiException.notFound("Заявка"));
    }

    private static Map<String, Object> result(String type, String outcome, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("outcome", outcome);
        if (detail != null) {
            m.put("detail", detail);
        }
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
