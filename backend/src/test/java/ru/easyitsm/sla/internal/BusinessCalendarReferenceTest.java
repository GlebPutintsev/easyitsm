package ru.easyitsm.sla.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Эталонные сценарии рабочего времени (SLA-03, NFR-05). Ожидаемые сроки посчитаны вручную; допуск NFR-05 —
 * 1 минута, движок обязан давать точное совпадение, поэтому сравнение строгое.
 */
class BusinessCalendarReferenceTest {

    static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    static final List<String> OFFICE = List.of("09:00-18:00", "09:00-18:00", "09:00-18:00", "09:00-18:00",
            "09:00-18:00", "", "");

    static BusinessCalendar office(ZoneId zone, Map<LocalDate, String> exceptions) {
        return new BusinessCalendar(zone, OFFICE, exceptions);
    }

    static Instant msk(String local) {
        return java.time.LocalDateTime.parse(local).atZone(MSK).toInstant();
    }

    static Stream<Arguments> scenarios() {
        Map<LocalDate, String> ruNovember = new LinkedHashMap<>();
        ruNovember.put(LocalDate.parse("2026-11-03"), "09:00-17:00"); // предпраздничный сокращённый день
        ruNovember.put(LocalDate.parse("2026-11-04"), "");            // День народного единства
        Map<LocalDate, String> newYear = new LinkedHashMap<>();
        for (int d = 1; d <= 8; d++) {
            newYear.put(LocalDate.of(2027, 1, d), "");
        }
        Map<LocalDate, String> workingSaturday = Map.of(LocalDate.parse("2026-11-07"), "09:00-18:00");
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        BusinessCalendar berlin24 = new BusinessCalendar(berlin, Collections.nCopies(7, "00:00-24:00"), Map.of());

        return Stream.of(
                Arguments.of("C01 круглосуточно 4 ч", BusinessCalendar.ALWAYS,
                        Instant.parse("2026-11-05T10:00:00Z"), Duration.ofHours(4), Instant.parse("2026-11-05T14:00:00Z")),
                Arguments.of("C02 перенос на следующий день", office(MSK, Map.of()),
                        msk("2026-11-05T16:00"), Duration.ofHours(4), msk("2026-11-06T11:00")),
                Arguments.of("C03 через выходные", office(MSK, Map.of()),
                        msk("2026-11-06T17:00"), Duration.ofHours(4), msk("2026-11-09T12:00")),
                Arguments.of("C04 старт в субботу", office(MSK, Map.of()),
                        msk("2026-11-07T10:00"), Duration.ofMinutes(30), msk("2026-11-09T09:30")),
                Arguments.of("C05 старт до начала дня", office(MSK, Map.of()),
                        msk("2026-11-09T07:00"), Duration.ofHours(1), msk("2026-11-09T10:00")),
                Arguments.of("C06 сокращённый день и праздник", office(MSK, ruNovember),
                        msk("2026-11-03T16:00"), Duration.ofHours(2), msk("2026-11-05T10:00")),
                Arguments.of("C07 новогодние каникулы, 2 рабочих дня", office(MSK, newYear),
                        msk("2026-12-30T17:00"), Duration.ofHours(18), msk("2027-01-11T17:00")),
                Arguments.of("C08 перенесённая рабочая суббота", office(MSK, workingSaturday),
                        msk("2026-11-06T17:00"), Duration.ofHours(2), msk("2026-11-07T10:00")),
                Arguments.of("C09 график в другом часовом поясе", office(ZoneId.of("Asia/Vladivostok"), Map.of()),
                        Instant.parse("2026-11-05T00:00:00Z"), Duration.ofHours(9), Instant.parse("2026-11-06T00:00:00Z")),
                Arguments.of("C10 переход на летнее время, сутки 23 ч", berlin24,
                        Instant.parse("2026-03-28T23:00:00Z"), Duration.ofHours(24), Instant.parse("2026-03-29T23:00:00Z")),
                Arguments.of("C11 рабочие часы через переход на летнее время", office(berlin, Map.of()),
                        Instant.parse("2026-03-27T16:00:00Z"), Duration.ofHours(2), Instant.parse("2026-03-30T08:00:00Z")),
                Arguments.of("C12 переход на зимнее время, сутки 25 ч", berlin24,
                        Instant.parse("2026-10-24T22:00:00Z"), Duration.ofHours(25), Instant.parse("2026-10-25T23:00:00Z")),
                Arguments.of("C13 срок ровно в конце рабочего дня", office(MSK, Map.of()),
                        msk("2026-11-05T09:00"), Duration.ofHours(9), msk("2026-11-05T18:00")),
                Arguments.of("C14 обед: два интервала в дне",
                        new BusinessCalendar(MSK, List.of("09:00-13:00,14:00-18:00", "09:00-13:00,14:00-18:00",
                                "09:00-13:00,14:00-18:00", "09:00-13:00,14:00-18:00", "09:00-13:00,14:00-18:00", "", ""),
                                Map.of()),
                        msk("2026-11-05T12:30"), Duration.ofHours(1), msk("2026-11-05T14:30")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void dueMatchesReference(String name, BusinessCalendar cal, Instant start, Duration target, Instant expected) {
        Instant due = cal.add(start, target.toMillis(), Gaps.NONE);
        assertThat(Duration.between(expected, due).abs()).isLessThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(due).isEqualTo(expected);
        // Обратная проверка: от старта до срока ровно цель рабочего времени.
        assertThat(cal.elapsedMs(start.toEpochMilli(), due.toEpochMilli(), Gaps.NONE)).isEqualTo(target.toMillis());
    }

    @Test
    void pausesAreExcluded() {
        BusinessCalendar cal = office(MSK, Map.of());
        // Работа чт 16:00–17:00 (1 ч), пауза чт 17:00 – пт 10:00, остаток 3 ч с пт 10:00.
        Gaps pause = Gaps.of(List.<Long[]>of(new Long[] {msk("2026-11-05T17:00").toEpochMilli(),
                msk("2026-11-06T10:00").toEpochMilli()}));
        assertThat(cal.add(msk("2026-11-05T16:00"), Duration.ofHours(4).toMillis(), pause))
                .isEqualTo(msk("2026-11-06T13:00"));
    }

    @Test
    void openPauseFreezesDue() {
        Gaps open = Gaps.of(List.<Long[]>of(new Long[] {Instant.parse("2026-11-05T11:00:00Z").toEpochMilli(), null}));
        assertThat(BusinessCalendar.ALWAYS.add(Instant.parse("2026-11-05T10:00:00Z"), 3_600_000, open))
                .isEqualTo(Instant.parse("2026-11-05T11:00:00Z"));
        assertThat(BusinessCalendar.ALWAYS.add(Instant.parse("2026-11-05T10:00:00Z"), 3_600_001, open)).isNull();
    }

    @Test
    void nominalDayIsMostCommonWorkingDay() {
        assertThat(office(MSK, Map.of()).nominalDayMs()).isEqualTo(Duration.ofHours(9).toMillis());
        assertThat(BusinessCalendar.ALWAYS.nominalDayMs()).isEqualTo(Duration.ofHours(24).toMillis());
    }

    @Test
    void invalidHoursAreRejected() {
        assertThatThrownBy(() -> DayHours.parse("18:00-09:00")).hasMessageContaining("09:00-13:00");
        assertThatThrownBy(() -> DayHours.parse("09:00-13:00,12:00-18:00")).hasMessageContaining("пересечений");
        assertThatThrownBy(() -> DayHours.parse("24:30-25:00")).isNotNull();
        assertThat(DayHours.parse("00:00-24:00")).containsExactly(0, 1440);
    }

    @Test
    void scheduleWithoutWorkingTimeDoesNotLoop() {
        var cal = new BusinessCalendar(MSK, Collections.nCopies(7, ""), Map.of());
        assertThatThrownBy(() -> cal.add(Instant.parse("2026-11-05T10:00:00Z"), 1000, Gaps.NONE))
                .isInstanceOf(IllegalStateException.class);
    }
}
