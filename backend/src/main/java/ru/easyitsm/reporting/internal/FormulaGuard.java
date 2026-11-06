package ru.easyitsm.reporting.internal;

/**
 * Защита выгрузок от внедрения формул (RPT-04, угроза T-17, OWASP CSV Injection). Текст, который табличный
 * редактор принял бы за формулу или команду — начинается с {@code = + - @}, табуляции или перевода строки,
 * в том числе после пробелов и в полноширинной записи, — получает апостроф в начале и открывается как текст.
 * Числа выгружаются числами и не экранируются.
 */
final class FormulaGuard {

    private FormulaGuard() {
    }

    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        int i = 0;
        while (i < value.length() && value.charAt(i) == ' ') {
            i++;
        }
        if (i == value.length()) {
            return value;
        }
        char c = value.charAt(i);
        boolean dangerous = c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' || c == '\n'
                || c == '＝' || c == '＋' || c == '－' || c == '＠';
        return dangerous ? "'" + value : value;
    }
}
