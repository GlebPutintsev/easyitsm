package ru.easyitsm.catalog.internal.form;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Set;

/**
 * Поле формы предложения. {@code min}/{@code max}: для чисел — значение, для текста — длина,
 * для множественного выбора — количество вариантов.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormField(
        String key,
        String type,
        String label,
        String help,
        String placeholder,
        boolean required,
        List<FieldOption> options,
        Double min,
        Double max,
        String pattern,
        String patternMessage,
        Condition visibleWhen,
        Condition requiredWhen,
        Object defaultValue) {

    public static final Set<String> TYPES =
            Set.of("text", "textarea", "number", "select", "multiselect", "checkbox", "date", "email", "section");

    public boolean hasValue() {
        return !"section".equals(type);
    }

    public boolean hasOptions() {
        return "select".equals(type) || "multiselect".equals(type);
    }
}
