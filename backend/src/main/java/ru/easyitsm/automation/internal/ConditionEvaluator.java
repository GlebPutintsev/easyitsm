package ru.easyitsm.automation.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Проверка условий по {@link Facts}. Логика трёхзначная: поле, которого нет в фактах (проверка правила вручную
 * без события), даёт «неизвестно» ({@code null}). При запуске по событию все поля события известны.
 */
final class ConditionEvaluator {

    /** Итог проверки: общий результат и результат каждого условия. null — зависит от данных события. */
    record Evaluation(Boolean matched, List<Boolean> items) {
    }

    private ConditionEvaluator() {
    }

    static Evaluation evaluate(Conditions conditions, Facts facts) {
        Conditions c = conditions == null ? Conditions.always() : conditions;
        List<Boolean> results = new ArrayList<>();
        for (Condition item : c.itemsOrEmpty()) {
            results.add(facts.has(item.field()) ? test(item, facts.get(item.field())) : null);
        }
        return new Evaluation(combine(c.any(), results), results);
    }

    private static Boolean combine(boolean any, List<Boolean> results) {
        if (results.isEmpty()) {
            return true;
        }
        boolean unknown = false;
        for (Boolean r : results) {
            if (r == null) {
                unknown = true;
            } else if (r == any) {
                return any; // any: первое true решает; all: первое false решает
            }
        }
        return unknown ? null : !any;
    }

    static boolean test(Condition c, Object actual) {
        return switch (c.op()) {
            case "eq" -> equalsValue(actual, c.value());
            case "neq" -> !equalsValue(actual, c.value());
            case "in" -> Facts.strings(c.value()).stream().anyMatch(v -> equalsValue(actual, v));
            case "not_in" -> Facts.strings(c.value()).stream().noneMatch(v -> equalsValue(actual, v));
            case "empty" -> isEmpty(actual);
            case "not_empty" -> !isEmpty(actual);
            case "contains" -> contains(actual, c.value());
            case "not_contains" -> !contains(actual, c.value());
            case "gte" -> compare(actual, c.value(), true);
            case "lte" -> compare(actual, c.value(), false);
            default -> false;
        };
    }

    private static boolean equalsValue(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == null && expected == null;
        }
        if (actual instanceof Double d) {
            Double e = number(expected);
            return e != null && Double.compare(d, e) == 0;
        }
        if (actual instanceof List<?> list) {
            return list.size() == 1 && lower(list.get(0)).equals(lower(expected));
        }
        return lower(actual).equals(lower(expected));
    }

    private static boolean isEmpty(Object actual) {
        return actual == null || (actual instanceof String s && s.isBlank()) || (actual instanceof List<?> l && l.isEmpty());
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual instanceof List<?> list) {
            String e = lower(expected);
            return list.stream().anyMatch(v -> lower(v).equals(e));
        }
        return lower(actual).contains(lower(expected));
    }

    /** Сравнение чисел; нечисловое значение не проходит ни gte, ни lte. */
    private static boolean compare(Object actual, Object expected, boolean greaterOrEqual) {
        Double a = number(actual);
        Double e = number(expected);
        if (a == null || e == null) {
            return false;
        }
        int cmp = Double.compare(a, e);
        return greaterOrEqual ? cmp >= 0 : cmp <= 0;
    }

    static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String lower(Object value) {
        return String.valueOf(value).strip().toLowerCase(Locale.ROOT);
    }
}
