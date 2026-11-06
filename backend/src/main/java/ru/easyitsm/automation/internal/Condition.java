package ru.easyitsm.automation.internal;

/**
 * Условие правила: поле, операция и значение. Операции: eq, neq, in, not_in (значение — список), empty,
 * not_empty (без значения), contains, not_contains (подстрока текста или элемент набора), gte, lte (числа).
 */
record Condition(String field, String op, Object value) {
}
