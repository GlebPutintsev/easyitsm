package ru.easyitsm.notifications.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Встроенные шаблоны (NTF-02). Тема — заголовок в центре уведомлений и в push, текст — тело письма; первая строка
 * текста — краткое описание в центре уведомлений. Переменные {@code {{имя}}}, блок {@code {{#имя}}…{{/имя}}}
 * выводится, если значение не пустое, {@code {{^имя}}…{{/имя}}} — если пустое.
 * Арендатор может заменить любой шаблон своим (таблица notification_template).
 */
final class Templates {

    private Templates() {
    }

    /** Вид уведомления: каналы по умолчанию и переменные для редактора шаблонов. */
    record Kind(String code, boolean email, boolean push, List<String> variables) {
    }

    record Template(String subject, String body) {
    }

    static final List<String> COMMON = List.of("product", "recipient.name", "ticket.number", "ticket.title",
            "ticket.link", "ticket.status", "ticket.priority", "actor.name", "confidential");

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static final Map<String, Template> RU = new LinkedHashMap<>();
    static final Map<String, Template> EN = new LinkedHashMap<>();

    static {
        kind("ticket_created", true, false);
        kind("ticket_assigned", true, true);
        kind("ticket_queued", false, true, "group.name");
        kind("ticket_reply", true, true, "comment.body");
        kind("ticket_requester_reply", true, true, "comment.body");
        kind("ticket_note", false, true, "comment.body");
        kind("ticket_mentioned", true, true, "comment.body");
        kind("ticket_watch", false, false, "comment.body");
        kind("ticket_pending", true, true, "reason");
        kind("ticket_resolved", true, true, "reason");
        kind("ticket_cancelled", true, false, "reason");
        kind("ticket_reopened", true, true, "reason");
        kind("sla_warning", false, true, "sla.percent", "sla.metric", "sla.due");
        kind("sla_breached", true, true, "sla.metric");
        kind("approval_requested", true, true, "approval.step", "approval.due", "approval.link");
        kind("approval_decided", true, true, "approval.outcome", "reason");
        KINDS.put("password_reset", new Kind("password_reset", true, false,
                List.of("product", "recipient.name", "reset.link", "reset.expires")));
        KINDS.put("message", new Kind("message", true, true, List.of("product", "recipient.name", "title", "body", "link")));

        ru("ticket_created", "Заявка {{ticket.number}} зарегистрирована", """
                Здравствуйте, {{recipient.name}}!

                Ваша заявка {{ticket.number}} «{{ticket.title}}» зарегистрирована. Мы сообщим, когда появятся новости.

                Открыть заявку: {{ticket.link}}

                Чтобы дополнить заявку, ответьте на это письмо.""");
        en("ticket_created", "Ticket {{ticket.number}} has been registered", """
                Hello {{recipient.name}},

                Your ticket {{ticket.number}} "{{ticket.title}}" has been registered. We will let you know about any updates.

                Open the ticket: {{ticket.link}}

                Reply to this email to add details.""");

        ru("ticket_assigned", "Вам назначена заявка {{ticket.number}}", """
                {{actor.name}} назначил(а) вам заявку {{ticket.number}} «{{ticket.title}}», приоритет {{ticket.priority}}.

                {{ticket.link}}""");
        en("ticket_assigned", "Ticket {{ticket.number}} is assigned to you", """
                {{actor.name}} assigned ticket {{ticket.number}} "{{ticket.title}}" to you, priority {{ticket.priority}}.

                {{ticket.link}}""");

        ru("ticket_queued", "Новая заявка {{ticket.number}} в очереди {{group.name}}", """
                Заявка {{ticket.number}} «{{ticket.title}}», приоритет {{ticket.priority}}, ждёт исполнителя.

                {{ticket.link}}""");
        en("ticket_queued", "New ticket {{ticket.number}} in {{group.name}} queue", """
                Ticket {{ticket.number}} "{{ticket.title}}", priority {{ticket.priority}}, is waiting for an assignee.

                {{ticket.link}}""");

        ru("ticket_reply", "Новый ответ по заявке {{ticket.number}}", """
                {{^confidential}}{{actor.name}} ответил(а) по заявке {{ticket.number}} «{{ticket.title}}»:

                {{comment.body}}{{/confidential}}{{#confidential}}По заявке {{ticket.number}} есть новый ответ. Откройте заявку, чтобы прочитать его.{{/confidential}}

                Открыть заявку: {{ticket.link}}

                Чтобы ответить, ответьте на это письмо.""");
        en("ticket_reply", "New reply on ticket {{ticket.number}}", """
                {{^confidential}}{{actor.name}} replied on ticket {{ticket.number}} "{{ticket.title}}":

                {{comment.body}}{{/confidential}}{{#confidential}}There is a new reply on ticket {{ticket.number}}. Open the ticket to read it.{{/confidential}}

                Open the ticket: {{ticket.link}}

                Reply to this email to respond.""");

        ru("ticket_requester_reply", "Заявитель ответил по заявке {{ticket.number}}", """
                {{actor.name}} ответил(а) по заявке {{ticket.number}} «{{ticket.title}}»{{^confidential}}:

                {{comment.body}}{{/confidential}}

                {{ticket.link}}""");
        en("ticket_requester_reply", "Requester replied on ticket {{ticket.number}}", """
                {{actor.name}} replied on ticket {{ticket.number}} "{{ticket.title}}"{{^confidential}}:

                {{comment.body}}{{/confidential}}

                {{ticket.link}}""");

        ru("ticket_note", "Внутренняя заметка в заявке {{ticket.number}}", """
                {{actor.name}} добавил(а) заметку в заявку {{ticket.number}} «{{ticket.title}}».

                {{ticket.link}}""");
        en("ticket_note", "Internal note on ticket {{ticket.number}}", """
                {{actor.name}} added a note to ticket {{ticket.number}} "{{ticket.title}}".

                {{ticket.link}}""");

        ru("ticket_mentioned", "Вас упомянули в заявке {{ticket.number}}", """
                {{actor.name}} упомянул(а) вас в заявке {{ticket.number}} «{{ticket.title}}»{{^confidential}}:

                {{comment.body}}{{/confidential}}

                {{ticket.link}}""");
        en("ticket_mentioned", "You were mentioned in ticket {{ticket.number}}", """
                {{actor.name}} mentioned you in ticket {{ticket.number}} "{{ticket.title}}"{{^confidential}}:

                {{comment.body}}{{/confidential}}

                {{ticket.link}}""");

        ru("ticket_watch", "Изменения в заявке {{ticket.number}}", """
                В заявке {{ticket.number}} «{{ticket.title}}», на которую вы подписаны, новые изменения. Статус: {{ticket.status}}.

                {{ticket.link}}""");
        en("ticket_watch", "Updates on ticket {{ticket.number}}", """
                Ticket {{ticket.number}} "{{ticket.title}}" you are watching has been updated. Status: {{ticket.status}}.

                {{ticket.link}}""");

        ru("ticket_pending", "Нужна ваша информация по заявке {{ticket.number}}", """
                Здравствуйте, {{recipient.name}}!

                Чтобы продолжить работу по заявке {{ticket.number}} «{{ticket.title}}», нам нужна ваша информация.{{#reason}}

                {{reason}}{{/reason}}

                Ответьте на это письмо или откройте заявку: {{ticket.link}}""");
        en("ticket_pending", "We need your input on ticket {{ticket.number}}", """
                Hello {{recipient.name}},

                To continue working on ticket {{ticket.number}} "{{ticket.title}}", we need more information from you.{{#reason}}

                {{reason}}{{/reason}}

                Reply to this email or open the ticket: {{ticket.link}}""");

        ru("ticket_resolved", "Заявка {{ticket.number}} решена", """
                Здравствуйте, {{recipient.name}}!

                Заявка {{ticket.number}} «{{ticket.title}}» решена.{{#reason}}

                Решение: {{reason}}{{/reason}}

                Если проблема осталась, ответьте на это письмо — заявка вернётся в работу.

                {{ticket.link}}""");
        en("ticket_resolved", "Ticket {{ticket.number}} has been resolved", """
                Hello {{recipient.name}},

                Ticket {{ticket.number}} "{{ticket.title}}" has been resolved.{{#reason}}

                Resolution: {{reason}}{{/reason}}

                If the problem persists, reply to this email and the ticket will be reopened.

                {{ticket.link}}""");

        ru("ticket_cancelled", "Заявка {{ticket.number}} отменена", """
                Здравствуйте, {{recipient.name}}!

                Заявка {{ticket.number}} «{{ticket.title}}» отменена.{{#reason}} Причина: {{reason}}{{/reason}}

                {{ticket.link}}""");
        en("ticket_cancelled", "Ticket {{ticket.number}} has been cancelled", """
                Hello {{recipient.name}},

                Ticket {{ticket.number}} "{{ticket.title}}" has been cancelled.{{#reason}} Reason: {{reason}}{{/reason}}

                {{ticket.link}}""");

        ru("ticket_reopened", "Заявка {{ticket.number}} возвращена в работу", """
                Заявитель вернул заявку {{ticket.number}} «{{ticket.title}}» в работу.

                {{ticket.link}}""");
        en("ticket_reopened", "Ticket {{ticket.number}} has been reopened", """
                The requester reopened ticket {{ticket.number}} "{{ticket.title}}".

                {{ticket.link}}""");

        ru("sla_warning", "SLA {{ticket.number}}: прошло {{sla.percent}}% срока", """
                По заявке {{ticket.number}} «{{ticket.title}}» прошло {{sla.percent}}% срока ({{sla.metric}}). Срок: {{sla.due}}.

                {{ticket.link}}""");
        en("sla_warning", "SLA {{ticket.number}}: {{sla.percent}}% of time elapsed", """
                Ticket {{ticket.number}} "{{ticket.title}}" has used {{sla.percent}}% of its {{sla.metric}} time. Due: {{sla.due}}.

                {{ticket.link}}""");

        ru("sla_breached", "Нарушен срок SLA по заявке {{ticket.number}}", """
                По заявке {{ticket.number}} «{{ticket.title}}», приоритет {{ticket.priority}}, нарушен срок: {{sla.metric}}.

                {{ticket.link}}""");
        en("sla_breached", "SLA breached on ticket {{ticket.number}}", """
                Ticket {{ticket.number}} "{{ticket.title}}", priority {{ticket.priority}}, breached its {{sla.metric}} target.

                {{ticket.link}}""");

        ru("approval_requested", "Нужно ваше согласование: {{ticket.number}}", """
                Здравствуйте, {{recipient.name}}!

                Запрос {{ticket.number}} «{{ticket.title}}» ждёт вашего решения{{#approval.step}} на шаге «{{approval.step}}»{{/approval.step}}.{{#approval.due}} Срок: {{approval.due}}.{{/approval.due}}

                Согласовать или отклонить: {{approval.link}}""");
        en("approval_requested", "Your approval is needed: {{ticket.number}}", """
                Hello {{recipient.name}},

                Request {{ticket.number}} "{{ticket.title}}" is waiting for your decision{{#approval.step}} at step "{{approval.step}}"{{/approval.step}}.{{#approval.due}} Due: {{approval.due}}.{{/approval.due}}

                Approve or reject: {{approval.link}}""");

        ru("approval_decided", "Запрос {{ticket.number}}: {{approval.outcome}}", """
                Здравствуйте, {{recipient.name}}!

                Ваш запрос {{ticket.number}} «{{ticket.title}}»: {{approval.outcome}}.{{#reason}}

                Причина: {{reason}}{{/reason}}

                {{ticket.link}}""");
        en("approval_decided", "Request {{ticket.number}}: {{approval.outcome}}", """
                Hello {{recipient.name}},

                Your request {{ticket.number}} "{{ticket.title}}": {{approval.outcome}}.{{#reason}}

                Reason: {{reason}}{{/reason}}

                {{ticket.link}}""");

        ru("password_reset", "{{product}}: сброс пароля", """
                Здравствуйте, {{recipient.name}}!

                Для вашей учётной записи запрошен сброс пароля. Чтобы задать новый пароль, перейдите по ссылке до {{reset.expires}}:

                {{reset.link}}

                Если вы не запрашивали сброс, просто проигнорируйте это письмо: пароль останется прежним.""");
        en("password_reset", "{{product}}: password reset", """
                Hello {{recipient.name}},

                A password reset was requested for your account. To set a new password, open this link before {{reset.expires}}:

                {{reset.link}}

                If you did not request a reset, ignore this email and your password will stay the same.""");

        ru("message", "{{title}}", """
                {{body}}{{#link}}

                {{link}}{{/link}}""");
        en("message", "{{title}}", """
                {{body}}{{#link}}

                {{link}}{{/link}}""");
    }

    private static void kind(String code, boolean email, boolean push, String... extra) {
        List<String> vars = new java.util.ArrayList<>(COMMON);
        vars.addAll(List.of(extra));
        KINDS.put(code, new Kind(code, email, push, List.copyOf(vars)));
    }

    private static void ru(String kind, String subject, String body) {
        RU.put(kind, new Template(subject, body));
    }

    private static void en(String kind, String subject, String body) {
        EN.put(kind, new Template(subject, body));
    }

    static Template builtIn(String kind, String locale) {
        Template t = "en".equals(locale) ? EN.get(kind) : RU.get(kind);
        return t != null ? t : RU.get(kind);
    }

    static String locale(String userLocale) {
        return userLocale != null && userLocale.toLowerCase().startsWith("en") ? "en" : "ru";
    }
}
