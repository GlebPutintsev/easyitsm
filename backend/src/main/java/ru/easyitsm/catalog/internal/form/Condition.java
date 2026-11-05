package ru.easyitsm.catalog.internal.form;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Условие на ответы формы. Лист: {@code field op value}, составное: {@code all} или {@code any}.
 * Используется для показа и обязательности полей (REQ-02) и для включения шага согласования (REQ-03).
 * Та же логика повторена во фронтенде ({@code features/catalog/form/conditions.ts}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Condition(String field, String op, Object value, List<Condition> all, List<Condition> any) {

    public static final Set<String> OPS = Set.of("eq", "neq", "in", "notIn", "filled", "empty", "gt", "lt", "contains");
    static final int MAX_DEPTH = 4;

    public boolean test(Map<String, Object> values) {
        if (all != null) {
            return all.stream().allMatch(c -> c.test(values));
        }
        if (any != null) {
            return any.stream().anyMatch(c -> c.test(values));
        }
        Object actual = values.get(field);
        return switch (op) {
            case "eq" -> same(actual, value);
            case "neq" -> !same(actual, value);
            case "in" -> value instanceof Collection<?> list && list.stream().anyMatch(v -> same(actual, v));
            case "notIn" -> !(value instanceof Collection<?> list && list.stream().anyMatch(v -> same(actual, v)));
            case "filled" -> filled(actual);
            case "empty" -> !filled(actual);
            case "gt" -> compare(actual, value) > 0;
            case "lt" -> compare(actual, value) < 0;
            case "contains" -> actual instanceof Collection<?> list && list.stream().anyMatch(v -> same(v, value));
            default -> false;
        };
    }

    /** Поля, на которые ссылается условие, включая вложенные. */
    public void collectFields(Collection<String> out) {
        if (all != null) {
            all.forEach(c -> c.collectFields(out));
        } else if (any != null) {
            any.forEach(c -> c.collectFields(out));
        } else if (field != null) {
            out.add(field);
        }
    }

    /** Возвращает текст ошибки или null, если условие корректно. */
    public String problem(int depth) {
        if (depth > MAX_DEPTH) {
            return "слишком глубокая вложенность условий";
        }
        if (all != null || any != null) {
            if (all != null && any != null) {
                return "в условии одновременно all и any";
            }
            List<Condition> parts = all != null ? all : any;
            if (parts.isEmpty()) {
                return "пустой список условий";
            }
            return parts.stream().map(c -> c == null ? "пустое условие" : c.problem(depth + 1))
                    .filter(Objects::nonNull).findFirst().orElse(null);
        }
        if (field == null || field.isBlank()) {
            return "в условии не указано поле";
        }
        if (op == null || !OPS.contains(op)) {
            return "неизвестная операция условия: " + op;
        }
        if ((op.equals("in") || op.equals("notIn")) && !(value instanceof Collection<?>)) {
            return "для операции " + op + " значение должно быть списком";
        }
        return null;
    }

    static boolean filled(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isBlank();
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return true;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return decimal(x).compareTo(decimal(y)) == 0;
        }
        if (a == null || b == null) {
            return a == b;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static int compare(Object a, Object b) {
        BigDecimal x = toDecimal(a);
        BigDecimal y = toDecimal(b);
        if (x == null || y == null) {
            // Даты в ISO-формате сравниваются как строки.
            if (a instanceof String s && b instanceof String t) {
                return s.compareTo(t);
            }
            return 0;
        }
        return x.compareTo(y);
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof Number n) {
            return decimal(n);
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal decimal(Number n) {
        return n instanceof BigDecimal d ? d : new BigDecimal(n.toString());
    }
}
