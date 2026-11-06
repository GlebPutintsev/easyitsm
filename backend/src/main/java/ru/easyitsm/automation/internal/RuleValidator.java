package ru.easyitsm.automation.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.platform.web.ApiException;

/**
 * Проверяет правило перед сохранением: событие, поля и операции условий, значения перечислений, параметры
 * действий, существование групп и пользователей в справочнике identity. Ошибка — 400 {@code invalid_rule}
 * с местом ошибки в тексте.
 */
@Component
class RuleValidator {

    static final int MAX_CONDITIONS = 20;
    static final int MAX_ACTIONS = 10;
    static final int MAX_TEXT = 10_000;

    private static final Set<String> OPS = Set.of("eq", "neq", "in", "not_in", "empty", "not_empty", "contains",
            "not_contains", "gte", "lte");

    /** Допустимые значения полей-перечислений. Поля вне карты — свободный текст или UUID. */
    private static final Map<String, Set<String>> ENUMS = Map.ofEntries(
            Map.entry("type", Set.of("incident", "request", "problem", "change", "task")),
            Map.entry("priority", Set.of("p1", "p2", "p3", "p4")),
            Map.entry("fromPriority", Set.of("p1", "p2", "p3", "p4")),
            Map.entry("impact", Set.of("high", "medium", "low")),
            Map.entry("urgency", Set.of("high", "medium", "low")),
            Map.entry("statusCategory", categories()),
            Map.entry("fromStatusCategory", categories()),
            Map.entry("channel", Set.of("portal", "email", "api", "chat", "monitoring", "agent")),
            Map.entry("confidential", Set.of("true", "false")),
            Map.entry("fromRequester", Set.of("true", "false")),
            Map.entry("visibility", Set.of("public", "internal")),
            Map.entry("escalationKind", Set.of("functional", "hierarchical")),
            Map.entry("slaKind", Set.of("sla", "ola", "uc")),
            Map.entry("slaMetric", Set.of("response", "resolution")));

    private final UserDirectory directory;

    RuleValidator(UserDirectory directory) {
        this.directory = directory;
    }

    private static Set<String> categories() {
        return Set.of("draft", "new", "approval", "assigned", "in_progress", "pending", "resolved", "closed",
                "cancelled");
    }

    void validate(RuleInput r) {
        if (r.name() == null || r.name().isBlank()) {
            throw invalid("Укажите название правила");
        }
        if (r.name().strip().length() > 200) {
            throw invalid("Название — не длиннее 200 символов");
        }
        if (r.description() != null && r.description().length() > 2000) {
            throw invalid("Описание — не длиннее 2000 символов");
        }
        Trigger trigger = Trigger.of(r.trigger()).orElseThrow(() -> invalid("Выберите событие правила"));
        validateConditions(trigger, r.conditions() == null ? Conditions.always() : r.conditions());
        validateActions(r.actions());
    }

    private void validateConditions(Trigger trigger, Conditions c) {
        if (c.match() != null && !Set.of("all", "any").contains(c.match())) {
            throw invalid("Условия объединяются через all или any");
        }
        List<Condition> items = c.itemsOrEmpty();
        if (items.size() > MAX_CONDITIONS) {
            throw invalid("Не больше " + MAX_CONDITIONS + " условий");
        }
        for (int i = 0; i < items.size(); i++) {
            String at = "Условие " + (i + 1) + ": ";
            Condition cond = items.get(i);
            if (cond == null || cond.field() == null || !trigger.allows(cond.field())) {
                throw invalid(at + "поле недоступно для этого события");
            }
            if (cond.op() == null || !OPS.contains(cond.op())) {
                throw invalid(at + "неизвестная операция");
            }
            validateOperation(at, cond);
        }
    }

    private void validateOperation(String at, Condition c) {
        String field = c.field();
        boolean number = Facts.NUMBER_FIELDS.contains(field);
        boolean list = Facts.LIST_FIELDS.contains(field);
        switch (c.op()) {
            case "empty", "not_empty" -> {
                return;
            }
            case "gte", "lte" -> {
                if (!number || ConditionEvaluator.number(c.value()) == null) {
                    throw invalid(at + "сравнение «больше или меньше» — только для чисел");
                }
                return;
            }
            case "contains", "not_contains" -> {
                if (!list && !"title".equals(field)) {
                    throw invalid(at + "«содержит» — только для темы и списка изменённых полей");
                }
            }
            case "in", "not_in" -> {
                if (!(c.value() instanceof List<?> values) || values.isEmpty()) {
                    throw invalid(at + "укажите хотя бы одно значение");
                }
            }
            default -> {
                if (list) {
                    throw invalid(at + "для списка изменённых полей используйте «содержит»");
                }
            }
        }
        List<String> values = Facts.strings(c.value());
        if (values.isEmpty() || values.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw invalid(at + "укажите значение");
        }
        for (String v : values) {
            if (v.length() > 500) {
                throw invalid(at + "значение длиннее 500 символов");
            }
            Set<String> allowed = ENUMS.get(field);
            if (allowed != null && !allowed.contains(v.strip().toLowerCase(java.util.Locale.ROOT))) {
                throw invalid(at + "недопустимое значение «" + v + "»");
            }
            if (Facts.UUID_FIELDS.contains(field) && uuid(v) == null) {
                throw invalid(at + "значение должно быть идентификатором");
            }
            if (number && ConditionEvaluator.number(v) == null) {
                throw invalid(at + "значение должно быть числом");
            }
        }
    }

    private void validateActions(List<Action> actions) {
        if (actions == null || actions.isEmpty()) {
            throw invalid("Добавьте хотя бы одно действие");
        }
        if (actions.size() > MAX_ACTIONS) {
            throw invalid("Не больше " + MAX_ACTIONS + " действий");
        }
        for (int i = 0; i < actions.size(); i++) {
            String at = "Действие " + (i + 1) + ": ";
            Action a = actions.get(i);
            if (a == null || a.type() == null || !Action.TYPES.contains(a.type())) {
                throw invalid(at + "неизвестный тип");
            }
            switch (a.type()) {
                case "assign" -> {
                    if (a.groupId() == null && a.assigneeId() == null) {
                        throw invalid(at + "укажите группу или исполнителя");
                    }
                    requireGroup(at, a.groupId());
                    requireUser(at, a.assigneeId());
                    if (a.groupId() != null && a.assigneeId() != null && !directory.isMember(a.groupId(), a.assigneeId())) {
                        throw invalid(at + "исполнитель не состоит в группе");
                    }
                }
                case "set_priority" -> {
                    if (a.priority() == null || !Set.of("P1", "P2", "P3", "P4").contains(a.priority())) {
                        throw invalid(at + "приоритет P1–P4");
                    }
                }
                case "set_status" -> {
                    if (a.status() == null || !a.status().matches("[a-z][a-z0-9_]{0,63}")) {
                        throw invalid(at + "укажите код статуса");
                    }
                    requireLength(at, a.body());
                }
                case "add_comment" -> {
                    if (a.body() == null || a.body().isBlank()) {
                        throw invalid(at + "укажите текст");
                    }
                    requireLength(at, a.body());
                    if (a.visibility() != null && !Set.of("public", "internal").contains(a.visibility())) {
                        throw invalid(at + "видимость public или internal");
                    }
                }
                case "add_watcher" -> {
                    if (a.userId() == null) {
                        throw invalid(at + "укажите пользователя");
                    }
                    requireUser(at, a.userId());
                }
                case "escalate" -> {
                    if (!"functional".equals(a.kind()) && !"hierarchical".equals(a.kind())) {
                        throw invalid(at + "эскалация functional или hierarchical");
                    }
                    if ("functional".equals(a.kind()) && a.groupId() == null) {
                        throw invalid(at + "для функциональной эскалации укажите группу");
                    }
                    requireGroup(at, a.groupId());
                    if (a.reason() == null || a.reason().isBlank()) {
                        throw invalid(at + "укажите причину эскалации");
                    }
                    requireLength(at, a.reason());
                }
                default -> throw invalid(at + "неизвестный тип");
            }
        }
    }

    private void requireGroup(String at, UUID groupId) {
        if (groupId != null && directory.findGroup(groupId).isEmpty()) {
            throw invalid(at + "группа не найдена");
        }
    }

    private void requireUser(String at, UUID userId) {
        if (userId != null && directory.findUser(userId).isEmpty()) {
            throw invalid(at + "пользователь не найден");
        }
    }

    private static void requireLength(String at, String text) {
        if (text != null && text.length() > MAX_TEXT) {
            throw invalid(at + "текст длиннее " + MAX_TEXT + " символов");
        }
    }

    static UUID uuid(String value) {
        try {
            return value != null && value.strip().length() == 36 ? UUID.fromString(value.strip()) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static ApiException invalid(String detail) {
        return ApiException.badRequest("invalid_rule", detail);
    }
}
