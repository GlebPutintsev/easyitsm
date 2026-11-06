package ru.easyitsm.sla.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.easyitsm.platform.web.ApiException;

/**
 * Рабочие часы одного дня в виде строки {@code "09:00-13:00,14:00-18:00"}. Пустая строка — нерабочий день,
 * {@code "00:00-24:00"} — круглые сутки. Внутри хранится как пары минут от начала дня {@code [from, to)}.
 */
final class DayHours {

    static final int DAY_MINUTES = 24 * 60;
    static final String ALL_DAY = "00:00-24:00";
    private static final Pattern RANGE = Pattern.compile("(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})");

    private DayHours() {
    }

    /** @return минуты вида {@code [from1, to1, from2, to2, ...]}, интервалы по возрастанию без пересечений */
    static int[] parse(String text) {
        if (text == null || text.isBlank()) {
            return new int[0];
        }
        List<Integer> out = new ArrayList<>();
        int prevEnd = -1;
        for (String part : text.split(",")) {
            Matcher m = RANGE.matcher(part.trim());
            if (!m.matches()) {
                throw invalid(text);
            }
            int from = minute(m.group(1), m.group(2), text);
            int to = minute(m.group(3), m.group(4), text);
            if (to <= from || from < prevEnd) {
                throw invalid(text);
            }
            out.add(from);
            out.add(to);
            prevEnd = to;
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Приводит строку к каноническому виду и проверяет её. */
    static String normalize(List<String> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return "";
        }
        String joined = String.join(",", ranges.stream().map(String::trim).toList());
        parse(joined);
        return joined;
    }

    static List<String> split(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.split(","));
    }

    static int totalMinutes(int[] ranges) {
        int sum = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            sum += ranges[i + 1] - ranges[i];
        }
        return sum;
    }

    private static int minute(String hh, String mm, String text) {
        int h = Integer.parseInt(hh);
        int m = Integer.parseInt(mm);
        if (m > 59 || h > 24 || (h == 24 && m != 0)) {
            throw invalid(text);
        }
        return h * 60 + m;
    }

    private static ApiException invalid(String text) {
        return ApiException.badRequest("invalid_hours",
                "Часы работы задаются как 09:00-13:00,14:00-18:00 по возрастанию без пересечений: " + text);
    }
}
