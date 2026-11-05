package ru.easyitsm.catalog.internal.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormValuesTest {

    static FormField field(String key, String type, boolean required) {
        return new FormField(key, type, key, null, null, required, null, null, null, null, null, null, null, null);
    }

    @Test
    void conditionsUseOnlyAcceptedValuesAbove() {
        var role = new FormField("role", "select", "Роль", null, null, true,
                List.of(new FieldOption("dev", "Dev"), new FieldOption("hr", "HR")), null, null, null, null, null, null,
                null);
        var repo = new FormField("repo", "text", "Репозиторий", null, null, false, null, null, null, null, null,
                new Condition("role", "eq", "dev", null, null), new Condition("role", "eq", "dev", null, null), null);
        var schema = new FormSchema(List.of(role, repo));

        assertThat(FormValues.validate(schema, Map.of("role", "hr", "repo", "x"))).containsOnlyKeys("role");
        assertThatThrownBy(() -> FormValues.validate(schema, Map.of("role", "dev")))
                .isInstanceOfSatisfying(FormValidationException.class,
                        e -> assertThat(e.errors()).containsEntry("repo", "required"));
    }

    @Test
    void typesAreCheckedAndCoerced() {
        var multi = new FormField("os", "multiselect", "ОС", null, null, false,
                List.of(new FieldOption("win", "Windows"), new FieldOption("mac", "macOS")), null, 1.0, null, null,
                null, null, null);
        var schema = new FormSchema(List.of(field("count", "number", true), field("date", "date", false),
                field("agree", "checkbox", true), multi, field("mail", "email", false)));

        var clean = FormValues.validate(schema, Map.of("count", "3", "date", "2026-12-01", "agree", true,
                "os", List.of("mac"), "mail", "a@b.ru"));
        assertThat(clean.get("count")).isEqualTo(new BigDecimal("3"));

        assertThatThrownBy(() -> FormValues.validate(schema, Map.of("count", "x", "date", "01.12.2026",
                "agree", false, "os", List.of("win", "mac"), "mail", "nope")))
                .isInstanceOfSatisfying(FormValidationException.class, e -> assertThat(e.errors())
                        .containsEntry("count", "type").containsEntry("date", "type")
                        .containsEntry("agree", "required").containsEntry("os", "max").containsEntry("mail", "email"));
    }

    @Test
    void compositeConditions() {
        var c = new Condition(null, null, null, List.of(
                new Condition("a", "gt", 5, null, null),
                new Condition(null, null, null, null, List.of(
                        new Condition("b", "in", List.of("x", "y"), null, null),
                        new Condition("c", "filled", null, null, null)))), null);
        assertThat(c.test(Map.of("a", new BigDecimal("6"), "b", "y"))).isTrue();
        assertThat(c.test(Map.of("a", 6, "c", ""))).isFalse();
        assertThat(c.test(Map.of("a", 4, "b", "x"))).isFalse();
        assertThat(new Condition("x", "bogus", null, null, null).problem(0)).isNotNull();
    }
}
