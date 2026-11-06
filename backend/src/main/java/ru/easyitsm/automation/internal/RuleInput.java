package ru.easyitsm.automation.internal;

import java.util.List;

/**
 * Тело создания и изменения правила. При изменении (PATCH) null означает «не менять»; при создании обязательны
 * {@code name}, {@code trigger} и хотя бы одно действие. Новое правило выключено, пока не передан
 * {@code enabled: true}.
 */
record RuleInput(
        String name,
        String description,
        String trigger,
        Conditions conditions,
        List<Action> actions,
        Boolean enabled,
        Integer position) {

    /** Значения изменения поверх текущего правила. */
    RuleInput over(Rule current) {
        return new RuleInput(
                name != null ? name : current.name(),
                description != null ? description : current.description(),
                trigger != null ? trigger : current.trigger(),
                conditions != null ? conditions : current.conditions(),
                actions != null ? actions : current.actions(),
                enabled != null ? enabled : current.enabled(),
                position != null ? position : current.position());
    }
}
