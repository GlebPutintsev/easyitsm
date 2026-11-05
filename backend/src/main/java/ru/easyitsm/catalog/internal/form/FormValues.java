package ru.easyitsm.catalog.internal.form;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Проверка ответов формы на сервере (ACC-01: проверка в браузере не считается защитой).
 * Скрытые по условию поля отбрасываются, неизвестные ключи игнорируются.
 */
public final class FormValues {

    static final int TEXT_MAX = 1_000;
    static final int TEXTAREA_MAX = 10_000;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");

    private FormValues() {
    }

    /** @return очищенные значения только видимых полей в порядке формы */
    public static Map<String, Object> validate(FormSchema schema, Map<String, Object> input) {
        Map<String, Object> raw = input == null ? Map.of() : input;
        Map<String, Object> clean = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        for (FormField f : schema.fields()) {
            if (!f.hasValue()) {
                continue;
            }
            // Условия считаются по уже принятым значениям полей выше: скрытое поле не влияет на нижние.
            if (f.visibleWhen() != null && !f.visibleWhen().test(clean)) {
                continue;
            }
            boolean required = f.isRequired() || (f.requiredWhen() != null && f.requiredWhen().test(clean));
            Object value = normalize(raw.get(f.key()));
            if (!Condition.filled(value)) {
                if (required) {
                    errors.put(f.key(), "required");
                }
                continue;
            }
            String error = check(f, value);
            if (error != null) {
                errors.put(f.key(), error);
                continue;
            }
            clean.put(f.key(), coerce(f, value));
        }
        if (!errors.isEmpty()) {
            throw new FormValidationException(errors);
        }
        return clean;
    }

    private static Object normalize(Object v) {
        return v instanceof String s ? s.strip() : v;
    }

    private static String check(FormField f, Object v) {
        return switch (f.type()) {
            case "text", "textarea", "email" -> {
                if (!(v instanceof String s)) {
                    yield "type";
                }
                int hardMax = f.type().equals("textarea") ? TEXTAREA_MAX : TEXT_MAX;
                if (s.length() > hardMax || (f.max() != null && s.length() > f.max())) {
                    yield "max";
                }
                if (f.min() != null && s.length() < f.min()) {
                    yield "min";
                }
                if (f.type().equals("email") && !EMAIL.matcher(s).matches()) {
                    yield "email";
                }
                if (f.pattern() != null && !f.pattern().isEmpty() && !Pattern.compile(f.pattern()).matcher(s).matches()) {
                    yield "pattern";
                }
                yield null;
            }
            case "number" -> {
                BigDecimal n = number(v);
                if (n == null) {
                    yield "type";
                }
                if (f.min() != null && n.compareTo(BigDecimal.valueOf(f.min())) < 0) {
                    yield "min";
                }
                if (f.max() != null && n.compareTo(BigDecimal.valueOf(f.max())) > 0) {
                    yield "max";
                }
                yield null;
            }
            case "select" -> v instanceof String s && optionValues(f).contains(s) ? null : "option";
            case "multiselect" -> {
                if (!(v instanceof Collection<?> list) || !list.stream().allMatch(x -> x instanceof String)) {
                    yield "type";
                }
                if (!optionValues(f).containsAll(list)) {
                    yield "option";
                }
                if (f.min() != null && list.size() < f.min()) {
                    yield "min";
                }
                if (f.max() != null && list.size() > f.max()) {
                    yield "max";
                }
                yield null;
            }
            case "checkbox" -> v instanceof Boolean ? null : "type";
            case "date" -> {
                if (!(v instanceof String s)) {
                    yield "type";
                }
                try {
                    LocalDate.parse(s);
                    yield null;
                } catch (DateTimeParseException e) {
                    yield "type";
                }
            }
            default -> "type";
        };
    }

    private static Object coerce(FormField f, Object v) {
        if (f.type().equals("number")) {
            return number(v);
        }
        if (f.type().equals("multiselect")) {
            // Без повторов, в порядке вариантов формы.
            Set<?> chosen = Set.copyOf((Collection<?>) v);
            List<String> ordered = new ArrayList<>();
            f.options().forEach(o -> {
                if (chosen.contains(o.value())) {
                    ordered.add(o.value());
                }
            });
            return ordered;
        }
        return v;
    }

    private static BigDecimal number(Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Set<String> optionValues(FormField f) {
        return f.options().stream().map(FieldOption::value).collect(Collectors.toSet());
    }
}
