package ru.easyitsm.automation.internal;

import java.util.List;

/** Условия правила: {@code all} — все должны выполниться, {@code any} — хотя бы одно. Пусто — всегда. */
record Conditions(String match, List<Condition> items) {

    static Conditions always() {
        return new Conditions("all", List.of());
    }

    List<Condition> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }

    boolean any() {
        return "any".equals(match);
    }
}
