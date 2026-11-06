package ru.easyitsm.reporting.internal;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.easyitsm.platform.web.ApiException;

/**
 * Период и фильтры дашборда (10.15 ТЗ: применяются ко всем виджетам). Период — календарные дни в часовом поясе
 * пользователя, {@code to} включительно. Без периода — последние 30 дней.
 *
 * @param groupId   группа; {@link #NONE} — заявки без группы
 * @param serviceId услуга (или предложение каталога); {@link #NONE} — без услуги
 */
record ReportFilter(LocalDate from, LocalDate to, ZoneId zone, String groupId, String type, String priority,
                    String serviceId, String lang) {

    static final String NONE = "none";
    static final int MAX_DAYS = 366;
    /** До этой длины периода точки графиков — дни, дальше — недели. */
    static final int DAILY_UP_TO = 62;

    static final Set<String> TYPES = Set.of("incident", "request", "problem", "change", "task");
    static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3", "P4");

    static ReportFilter of(String from, String to, String tz, String groupId, String type, String priority,
                           String serviceId, String lang) {
        ZoneId zone = zone(tz);
        LocalDate end = to == null || to.isBlank() ? LocalDate.now(zone) : date(to, "to");
        LocalDate start = from == null || from.isBlank() ? end.minusDays(29) : date(from, "from");
        if (start.isAfter(end)) {
            throw ApiException.badRequest("invalid_period", "Начало периода позже конца");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw ApiException.badRequest("invalid_period", "Период не длиннее " + MAX_DAYS + " дней");
        }
        if (type != null && !type.isBlank() && !TYPES.contains(type)) {
            throw ApiException.badRequest("invalid_type", "Неизвестный тип заявки");
        }
        if (priority != null && !priority.isBlank() && !PRIORITIES.contains(priority)) {
            throw ApiException.badRequest("invalid_priority", "Неизвестный приоритет");
        }
        return new ReportFilter(start, end, zone, idOrNone(groupId, "groupId"), blank(type), blank(priority),
                idOrNone(serviceId, "serviceId"), "en".equals(lang) ? "en" : "ru");
    }

    Instant fromInstant() {
        return from.atStartOfDay(zone).toInstant();
    }

    /** Конец периода, не включительно: начало дня после {@code to}. */
    Instant toInstant() {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** day или week: шаг точек на графиках. */
    String granularity() {
        return days() <= DAILY_UP_TO ? "day" : "week";
    }

    /** Имя пояса для PostgreSQL (AT TIME ZONE). */
    String pgZone() {
        return zone instanceof ZoneOffset o ? (o.getTotalSeconds() == 0 ? "UTC" : o.getId()) : zone.getId();
    }

    UUID groupUuid() {
        return groupId == null || NONE.equals(groupId) ? null : UUID.fromString(groupId);
    }

    /** Применённые фильтры для журнала выгрузок и шапки файла. */
    Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        m.put("tz", zone.getId());
        if (groupId != null) {
            m.put("groupId", groupId);
        }
        if (type != null) {
            m.put("type", type);
        }
        if (priority != null) {
            m.put("priority", priority);
        }
        if (serviceId != null) {
            m.put("serviceId", serviceId);
        }
        return m;
    }

    /**
     * Условие на строки витрины (псевдоним {@code f}) по фильтрам и области пользователя. Параметры складываются
     * в {@code params}.
     */
    String where(ReportScope scope, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("true");
        params.put("from", Timestamp.from(fromInstant()));
        params.put("to", Timestamp.from(toInstant()));
        params.put("tz", pgZone());
        params.put("now", Timestamp.from(Instant.now()));
        if (!scope.allTickets()) {
            sql.append(scopeSql(scope, params));
        }
        if (groupId != null) {
            if (NONE.equals(groupId)) {
                sql.append(" and f.group_id is null");
            } else {
                sql.append(" and f.group_id = :groupId");
                params.put("groupId", UUID.fromString(groupId));
            }
        }
        if (serviceId != null) {
            if (NONE.equals(serviceId)) {
                sql.append(" and f.service_id is null");
            } else {
                sql.append(" and f.service_id = :serviceId");
                params.put("serviceId", UUID.fromString(serviceId));
            }
        }
        if (type != null) {
            sql.append(" and f.type = :type");
            params.put("type", type);
        }
        if (priority != null) {
            sql.append(" and f.priority = :priority");
            params.put("priority", priority);
        }
        return sql.toString();
    }

    /** Свои группы и назначенные на пользователя заявки. */
    static String scopeSql(ReportScope scope, Map<String, Object> params) {
        params.put("me", scope.userId());
        if (scope.groupIds().isEmpty()) {
            return " and f.assignee_id = :me";
        }
        params.put("myGroups", scope.groupIds());
        return " and (f.group_id in (:myGroups) or f.assignee_id = :me)";
    }

    static Map<String, Object> params() {
        return new HashMap<>();
    }

    private static ZoneId zone(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("invalid_timezone", "Неизвестный часовой пояс");
        }
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("invalid_" + field, "Дата в формате ГГГГ-ММ-ДД");
        }
    }

    private static String idOrNone(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (NONE.equals(value)) {
            return NONE;
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_" + field, "Некорректный идентификатор");
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
