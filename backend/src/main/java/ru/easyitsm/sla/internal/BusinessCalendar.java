package ru.easyitsm.sla.internal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Рабочее время для расчёта SLA (SLA-03): недельный график в часовом поясе графика и исключения по датам
 * (праздники, сокращённые и перенесённые рабочие дни).
 * <p>
 * Расчёт точный до миллисекунды: время переводится в границы рабочих интервалов через {@link ZoneId}, поэтому
 * переходы на летнее время, полночь и 24:00 учитываются без приближений (NFR-05).
 */
final class BusinessCalendar {

    /** Круглосуточно, без праздников. */
    static final BusinessCalendar ALWAYS = new BusinessCalendar(ZoneOffset.UTC,
            Collections.nCopies(7, DayHours.ALL_DAY), Map.of());

    /** Предел поиска вперёд: график без рабочего времени не должен зациклить расчёт. */
    private static final int MAX_DAYS = 3700;

    private final ZoneId zone;
    private final int[][] week;
    private final Map<LocalDate, int[]> exceptions;

    /**
     * @param week       семь строк часов с понедельника по воскресенье
     * @param exceptions часы на конкретные даты: пустая строка — выходной, иначе — особые рабочие часы
     */
    BusinessCalendar(ZoneId zone, List<String> week, Map<LocalDate, String> exceptions) {
        if (week.size() != 7) {
            throw new IllegalArgumentException("Нужно 7 дней недели");
        }
        this.zone = zone;
        this.week = week.stream().map(DayHours::parse).toArray(int[][]::new);
        Map<LocalDate, int[]> ex = new HashMap<>();
        exceptions.forEach((d, h) -> ex.put(d, DayHours.parse(h)));
        this.exceptions = ex;
    }

    ZoneId zone() {
        return zone;
    }

    /** Длина обычного рабочего дня: самая частая ненулевая длина дня недели, при равенстве — бо́льшая. */
    long nominalDayMs() {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int[] day : week) {
            int m = DayHours.totalMinutes(day);
            if (m > 0) {
                freq.merge(m, 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .max((a, b) -> a.getValue().equals(b.getValue())
                        ? Integer.compare(a.getKey(), b.getKey())
                        : Integer.compare(a.getValue(), b.getValue()))
                .map(e -> e.getKey() * 60_000L)
                .orElse(0L);
    }

    boolean hasWorkingTime() {
        return Arrays.stream(week).anyMatch(d -> d.length > 0);
    }

    /** Рабочее время между {@code start} и {@code end} за вычетом исключённых интервалов. */
    long elapsedMs(long start, long end, Gaps gaps) {
        if (end <= start) {
            return 0;
        }
        long sum = 0;
        LocalDate last = localDate(end);
        for (LocalDate d = localDate(start).minusDays(1); !d.isAfter(last); d = d.plusDays(1)) {
            int[] h = hoursOf(d);
            for (int i = 0; i < h.length; i += 2) {
                long s = Math.max(epochMs(d, h[i]), start);
                long e = Math.min(epochMs(d, h[i + 1]), end);
                if (e > s) {
                    sum += gaps.uncovered(s, e);
                }
            }
        }
        return sum;
    }

    /**
     * Момент, когда от {@code start} накопится {@code amountMs} рабочего времени без исключённых интервалов.
     *
     * @return время в миллисекундах эпохи или {@code null}, если его не достичь: счётчик стоит на незакрытой паузе
     */
    Long addMs(long start, long amountMs, Gaps gaps) {
        if (amountMs <= 0) {
            return start;
        }
        long need = amountMs;
        long openFrom = gaps.openFrom();
        LocalDate d = localDate(start).minusDays(1);
        for (int n = 0; n < MAX_DAYS; n++, d = d.plusDays(1)) {
            int[] h = hoursOf(d);
            for (int i = 0; i < h.length; i += 2) {
                long s = Math.max(epochMs(d, h[i]), start);
                long e = epochMs(d, h[i + 1]);
                if (e <= s) {
                    continue;
                }
                if (s >= openFrom) {
                    return null;
                }
                long r = gaps.reach(s, e, need);
                if (r >= 0) {
                    return r;
                }
                need -= -r - 1;
            }
            if (epochMs(d.plusDays(1), 0) >= openFrom) {
                return null;
            }
        }
        throw new IllegalStateException("В графике нет рабочего времени на " + MAX_DAYS + " дней вперёд");
    }

    Instant add(Instant start, long amountMs, Gaps gaps) {
        Long r = addMs(start.toEpochMilli(), amountMs, gaps);
        return r == null ? null : Instant.ofEpochMilli(r);
    }

    private int[] hoursOf(LocalDate d) {
        int[] ex = exceptions.get(d);
        return ex != null ? ex : week[d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue()];
    }

    private LocalDate localDate(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate();
    }

    /** Минута дня в местном времени графика. 24:00 — начало следующих суток; время в «дыре» перехода сдвигается вперёд. */
    private long epochMs(LocalDate d, int minute) {
        if (minute == DayHours.DAY_MINUTES) {
            return d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        if (minute == 0) {
            return d.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        return d.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli();
    }
}
