package ru.easyitsm.tickets.internal;

import java.util.ArrayList;
import java.util.List;
import ru.easyitsm.tickets.api.TicketType;

/** Циклы по умолчанию: инцидент 5.1, запрос 5.4, изменение 5.6, заготовки проблем и подзадач. */
final class DefaultWorkflows {

    private static final List<String> SUPPORT = List.of("AGT", "LEAD", "MIM");
    private static final List<String> SUPPORT_AND_REQUESTER = List.of("requester", "AGT", "LEAD", "MIM");

    private DefaultWorkflows() {
    }

    static Workflow of(TicketType type) {
        return switch (type) {
            case INCIDENT -> incident();
            case REQUEST -> request();
            case PROBLEM -> generic(type, List.of("AGT", "LEAD", "PRB", "MIM"));
            case CHANGE -> change();
            case TASK -> task();
        };
    }

    private static Workflow.Status s(String code, String name, String category) {
        return new Workflow.Status(code, name, category, false);
    }

    private static Workflow.Status initial(String code, String name, String category) {
        return new Workflow.Status(code, name, category, true);
    }

    private static Workflow.Transition t(String from, String to, String name, List<String> roles, String... required) {
        return new Workflow.Transition(from, to, name, roles, List.of(required), "manual");
    }

    private static Workflow.Transition auto(String from, String to, String name, String trigger) {
        return new Workflow.Transition(from, to, name, List.of("system"), List.of(), trigger);
    }

    static Workflow incident() {
        var statuses = List.of(
                initial("new", "Новая", "new"),
                s("assigned", "Назначена", "assigned"),
                s("in_progress", "В работе", "in_progress"),
                s("pending", "Ожидание", "pending"),
                s("resolved", "Решена", "resolved"),
                s("closed", "Закрыта", "closed"),
                s("cancelled", "Отменена", "cancelled"));
        var tr = new ArrayList<Workflow.Transition>();
        tr.add(t("new", "assigned", "Назначить", SUPPORT, "group"));
        tr.add(auto("new", "assigned", "Назначена правилом", "group_assigned"));
        tr.add(t("new", "in_progress", "Взять в работу", SUPPORT, "assignee"));
        tr.add(t("assigned", "in_progress", "Взять в работу", SUPPORT, "assignee"));
        tr.add(t("in_progress", "pending", "Приостановить", List.of("AGT", "LEAD", "MIM", "assignee"), "reason_code",
                "pending_until"));
        tr.add(t("pending", "in_progress", "Возобновить", SUPPORT));
        tr.add(auto("pending", "in_progress", "Ответ заявителя", "requester_reply"));
        tr.add(auto("pending", "in_progress", "Срок ожидания истёк", "timer"));
        tr.add(t("in_progress", "resolved", "Решить", SUPPORT, "resolution"));
        tr.add(t("pending", "resolved", "Решить", SUPPORT, "resolution"));
        tr.add(t("resolved", "in_progress", "Возобновить", List.of("requester"), "comment"));
        tr.add(t("resolved", "in_progress", "Возобновить", SUPPORT));
        tr.add(auto("resolved", "in_progress", "Ответ заявителя", "requester_reply"));
        tr.add(t("resolved", "closed", "Подтвердить решение", List.of("requester", "LEAD", "MIM")));
        tr.add(auto("resolved", "closed", "Автозакрытие", "timer"));
        tr.add(t("new", "cancelled", "Отменить", SUPPORT_AND_REQUESTER, "comment"));
        tr.add(t("assigned", "cancelled", "Отменить", SUPPORT_AND_REQUESTER, "comment"));
        return new Workflow("incident", statuses, tr, null);
    }

    static Workflow request() {
        var statuses = List.of(
                s("draft", "Черновик", "draft"),
                initial("new", "Новая", "new"),
                s("pending_approval", "На согласовании", "approval"),
                s("approved", "Согласована", "new"),
                s("in_progress", "В работе", "in_progress"),
                s("pending", "Ожидание", "pending"),
                s("fulfilled", "Выполнена", "resolved"),
                s("closed", "Закрыта", "closed"),
                s("rejected", "Отклонена", "cancelled"),
                s("cancelled", "Отменена", "cancelled"));
        var tr = new ArrayList<Workflow.Transition>();
        tr.add(t("draft", "new", "Отправить", List.of("requester", "AGT", "LEAD")));
        tr.add(t("new", "pending_approval", "На согласование", List.of("AGT", "LEAD", "system")));
        tr.add(auto("pending_approval", "approved", "Согласована", "approval"));
        tr.add(new Workflow.Transition("pending_approval", "rejected", "Отклонена", List.of("system"),
                List.of("comment"), "approval"));
        tr.add(t("new", "in_progress", "Взять в работу", SUPPORT, "assignee"));
        tr.add(t("approved", "in_progress", "Взять в работу", SUPPORT, "assignee"));
        tr.add(t("in_progress", "pending", "Приостановить", List.of("AGT", "LEAD", "MIM", "assignee"), "reason_code",
                "pending_until"));
        tr.add(t("pending", "in_progress", "Возобновить", SUPPORT));
        tr.add(auto("pending", "in_progress", "Ответ заявителя", "requester_reply"));
        tr.add(auto("pending", "in_progress", "Срок ожидания истёк", "timer"));
        tr.add(t("in_progress", "fulfilled", "Выполнить", SUPPORT, "resolution"));
        tr.add(t("pending", "fulfilled", "Выполнить", SUPPORT, "resolution"));
        tr.add(t("fulfilled", "in_progress", "Возобновить", List.of("requester"), "comment"));
        tr.add(t("fulfilled", "in_progress", "Возобновить", SUPPORT));
        tr.add(auto("fulfilled", "in_progress", "Ответ заявителя", "requester_reply"));
        tr.add(t("fulfilled", "closed", "Подтвердить выполнение", List.of("requester", "LEAD")));
        tr.add(auto("fulfilled", "closed", "Автозакрытие", "timer"));
        for (String from : List.of("draft", "new", "pending_approval", "approved", "in_progress", "pending")) {
            tr.add(t(from, "cancelled", "Отменить", List.of("requester", "AGT", "LEAD"), "comment"));
        }
        return new Workflow("request", statuses, tr, null);
    }

    /**
     * Изменение (5.6). Переходы выполняет только модуль changes от имени системы: он проверяет планы, риск,
     * маршрут согласования, окно и разбор, поэтому пользователь не может сменить статус в обход этих проверок.
     */
    static Workflow change() {
        var statuses = List.of(
                initial("draft", "Черновик", "draft"),
                s("assessment", "Оценка", "new"),
                s("approval", "Согласование", "approval"),
                s("scheduled", "Запланировано", "assigned"),
                s("implementation", "Внедрение", "in_progress"),
                s("verification", "Проверка", "in_progress"),
                s("review", "Разбор", "resolved"),
                s("closed", "Закрыто", "closed"),
                s("rejected", "Отклонено", "cancelled"),
                s("cancelled", "Отменено", "cancelled"));
        var system = List.of("system");
        var tr = new ArrayList<Workflow.Transition>();
        tr.add(t("draft", "assessment", "На оценку", system));
        tr.add(t("draft", "approval", "На согласование", system));
        tr.add(t("draft", "scheduled", "Запланировать", system));
        tr.add(t("assessment", "draft", "Вернуть на доработку", system));
        tr.add(t("assessment", "approval", "На согласование", system));
        tr.add(t("approval", "scheduled", "Согласовано", system));
        tr.add(t("approval", "rejected", "Отклонено", system));
        tr.add(t("approval", "assessment", "Вернуть на оценку", system));
        tr.add(t("scheduled", "approval", "Пересогласовать", system));
        tr.add(t("scheduled", "assessment", "Вернуть на оценку", system));
        tr.add(t("scheduled", "implementation", "Начать внедрение", system));
        tr.add(t("implementation", "verification", "На проверку", system));
        tr.add(t("implementation", "review", "Завершить", system));
        tr.add(t("verification", "implementation", "Вернуть во внедрение", system));
        tr.add(t("verification", "review", "Завершить", system));
        tr.add(t("review", "closed", "Закрыть", system));
        for (String from : List.of("draft", "assessment", "approval", "scheduled")) {
            tr.add(t(from, "cancelled", "Отменить", system));
        }
        return new Workflow("change", statuses, tr, null);
    }

    static Workflow generic(TicketType type, List<String> roles) {
        var statuses = List.of(
                initial("new", "Новая", "new"),
                s("in_progress", "В работе", "in_progress"),
                s("pending", "Ожидание", "pending"),
                s("resolved", "Решена", "resolved"),
                s("closed", "Закрыта", "closed"),
                s("cancelled", "Отменена", "cancelled"));
        var tr = new ArrayList<Workflow.Transition>();
        tr.add(t("new", "in_progress", "Взять в работу", roles, "assignee"));
        tr.add(t("in_progress", "pending", "Приостановить", roles, "reason_code"));
        tr.add(t("pending", "in_progress", "Возобновить", roles));
        tr.add(auto("pending", "in_progress", "Срок ожидания истёк", "timer"));
        tr.add(t("in_progress", "resolved", "Решить", roles, "resolution"));
        tr.add(t("resolved", "in_progress", "Возобновить", roles));
        tr.add(t("resolved", "closed", "Закрыть", roles));
        tr.add(t("new", "cancelled", "Отменить", roles, "comment"));
        tr.add(t("in_progress", "cancelled", "Отменить", roles, "comment"));
        return new Workflow(type.code(), statuses, tr, null);
    }

    static Workflow task() {
        var roles = List.of("AGT", "LEAD", "MIM", "CHG", "PRB", "assignee");
        var statuses = List.of(
                initial("new", "Новая", "new"),
                s("in_progress", "В работе", "in_progress"),
                s("pending", "Ожидание", "pending"),
                s("done", "Выполнена", "closed"),
                s("cancelled", "Отменена", "cancelled"));
        var tr = new ArrayList<Workflow.Transition>();
        tr.add(t("new", "in_progress", "Взять в работу", roles));
        tr.add(t("in_progress", "pending", "Приостановить", roles, "reason_code"));
        tr.add(t("pending", "in_progress", "Возобновить", roles));
        tr.add(auto("pending", "in_progress", "Срок ожидания истёк", "timer"));
        tr.add(t("new", "done", "Выполнить", roles));
        tr.add(t("in_progress", "done", "Выполнить", roles));
        tr.add(t("pending", "done", "Выполнить", roles));
        tr.add(t("new", "cancelled", "Отменить", roles, "comment"));
        tr.add(t("in_progress", "cancelled", "Отменить", roles, "comment"));
        tr.add(t("pending", "cancelled", "Отменить", roles, "comment"));
        return new Workflow("task", statuses, tr, null);
    }
}
