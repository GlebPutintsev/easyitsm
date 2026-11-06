package ru.easyitsm.reporting.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.reporting.internal.NameBook.Names;
import ru.easyitsm.reporting.internal.ReportViews.TicketRow;

/**
 * Провал из дашборда в заявки (10.15: «клик по точке показывает заявки в пределах прав»). Показатель и
 * измерения приходят из {@code drill} строки и колонки виджета, период и фильтры — из дашборда.
 * Конфиденциальные заявки в список не попадают; список ограничен областью {@link ReportScope#drillAll()}.
 */
@Service
class TicketDrill {

    /** Показатель → условие на строку витрины. */
    static final Map<String, String> METRICS = Map.ofEntries(
            Map.entry("created", "f.created_at >= :from and f.created_at < :to"),
            Map.entry("resolved", "f.resolved_at >= :from and f.resolved_at < :to"),
            Map.entry("responded", "f.first_response_at >= :from and f.first_response_at < :to"),
            Map.entry("open", Dashboards.ACTIVE),
            Map.entry("unassigned", Dashboards.ACTIVE + " and f.assignee_id is null"),
            Map.entry("overdue", Dashboards.ACTIVE + " and f.due_at < :now"),
            Map.entry("breachedOpen", Dashboards.ACTIVE
                    + " and (f.response_breached_at is not null or f.resolution_breached_at is not null)"),
            Map.entry("slaResolved", "f.has_sla and f.resolved_at >= :from and f.resolved_at < :to"),
            Map.entry("slaMissed", "f.has_sla and f.resolved_at >= :from and f.resolved_at < :to"
                    + " and (f.response_breached_at is not null or f.resolution_breached_at is not null)"),
            Map.entry("responseMissed", "f.has_sla and f.first_response_at >= :from and f.first_response_at < :to"
                    + " and f.response_breached_at is not null"),
            Map.entry("resolutionMissed", "f.has_sla and f.resolved_at >= :from and f.resolved_at < :to"
                    + " and f.resolution_breached_at is not null"),
            Map.entry("breached", breach(null)),
            Map.entry("breachedResponse", breach("response")),
            Map.entry("breachedResolution", breach("resolution")),
            Map.entry("reopened", "f.resolved_at >= :from and f.resolved_at < :to and f.reopened_count > 0"),
            Map.entry("repeat", "f.created_at >= :from and f.created_at < :to and " + Dashboards.REPEAT),
            Map.entry("rated", "f.csat_at >= :from and f.csat_at < :to"));

    static final Set<String> STATUSES = Set.of("draft", "new", "approval", "assigned", "in_progress", "pending",
            "resolved", "closed", "cancelled");
    static final Set<String> CHANNELS = Set.of("portal", "email", "api", "chat", "monitoring", "agent", "none");

    private static final String COLUMNS = """
            f.ticket_id, f.number, f.type, f.title, f.status_code, f.status_category, f.priority, f.group_id,
            f.service_id, f.assignee_id, f.created_at, f.first_response_at, f.resolved_at,
            (f.response_breached_at is not null or f.resolution_breached_at is not null) as sla_breached,
            f.csat_score""";

    /**
     * Параметры провала.
     *
     * @param date начало точки графика (день или неделя дашборда)
     * @param age  корзина возраста открытой заявки
     */
    record Drill(String metric, String status, String priority, String groupId, String serviceId, String type,
                 String channel, LocalDate date, String age, Integer score) {

        static Drill of(String metric, String status, String priority, String groupId, String serviceId,
                        String type, String channel, String date, String age, String score) {
            if (metric == null || !METRICS.containsKey(metric)) {
                throw ApiException.badRequest("invalid_metric", "Неизвестный показатель");
            }
            if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
                throw ApiException.badRequest("invalid_status", "Неизвестная категория статуса");
            }
            if (channel != null && !channel.isBlank() && !CHANNELS.contains(channel)) {
                throw ApiException.badRequest("invalid_channel", "Неизвестный канал");
            }
            if (age != null && !age.isBlank() && !Dashboards.AGES.contains(age)) {
                throw ApiException.badRequest("invalid_age", "Неизвестный возраст");
            }
            Integer s = null;
            if (score != null && !score.isBlank()) {
                try {
                    s = Integer.parseInt(score);
                } catch (NumberFormatException e) {
                    s = 0;
                }
                if (s < 1 || s > 5) {
                    throw ApiException.badRequest("invalid_score", "Оценка от 1 до 5");
                }
            }
            LocalDate d = null;
            if (date != null && !date.isBlank()) {
                try {
                    d = LocalDate.parse(date);
                } catch (java.time.DateTimeException e) {
                    throw ApiException.badRequest("invalid_date", "Дата в формате ГГГГ-ММ-ДД");
                }
            }
            return new Drill(metric, blank(status), blank(priority), blank(groupId), blank(serviceId), blank(type),
                    blank(channel), d, blank(age), s);
        }

        Map<String, String> describe() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("metric", metric);
            put(m, "status", status);
            put(m, "drillPriority", priority);
            put(m, "drillGroupId", groupId);
            put(m, "drillServiceId", serviceId);
            put(m, "drillType", type);
            put(m, "channel", channel);
            put(m, "date", date == null ? null : date.toString());
            put(m, "age", age);
            put(m, "score", score == null ? null : score.toString());
            return m;
        }

        private static void put(Map<String, String> m, String k, String v) {
            if (v != null) {
                m.put(k, v);
            }
        }

        private static String blank(String v) {
            return v == null || v.isBlank() ? null : v;
        }
    }

    private final JdbcClient jdbc;
    private final NameBook names;

    TicketDrill(JdbcClient jdbc, NameBook names) {
        this.jdbc = jdbc;
        this.names = names;
    }

    @Transactional(readOnly = true)
    CursorPage<TicketRow> page(ReportFilter filter, Drill drill, ReportScope scope, PageParams page) {
        scope.requireDrill();
        Map<String, Object> p = ReportFilter.params();
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from reporting_ticket_fact f where ")
                .append(where(filter, drill, scope, p));
        KeysetCursor cursor = page.keyset();
        if (cursor != null) {
            sql.append(" and (f.created_at, f.ticket_id) < (:cursorAt, :cursorId)");
            try {
                p.put("cursorAt", Timestamp.from(Instant.parse(cursor.sortValue())));
            } catch (java.time.DateTimeException e) {
                throw ApiException.badRequest("invalid_cursor", "Некорректный курсор");
            }
            p.put("cursorId", cursor.id());
        }
        sql.append(" order by f.created_at desc, f.ticket_id desc limit :limit");
        p.put("limit", page.limit() + 1);
        Names n = names.load(filter.lang());
        List<TicketRow> rows = jdbc.sql(sql.toString()).params(p).query((rs, i) -> row(rs, n)).list();
        return CursorPage.of(rows, page.limit(), r -> new KeysetCursor(r.createdAt().toString(), r.id()));
    }

    @Transactional(readOnly = true)
    long count(ReportFilter filter, Drill drill, ReportScope scope) {
        scope.requireDrill();
        Map<String, Object> p = ReportFilter.params();
        return jdbc.sql("select count(*) from reporting_ticket_fact f where " + where(filter, drill, scope, p))
                .params(p).query(Long.class).single();
    }

    /** Все строки для выгрузки, не больше {@code limit}. */
    @Transactional(readOnly = true)
    List<TicketRow> all(ReportFilter filter, Drill drill, ReportScope scope, int limit) {
        scope.requireDrill();
        Map<String, Object> p = ReportFilter.params();
        p.put("limit", limit);
        Names n = names.load(filter.lang());
        List<TicketRow> out = new ArrayList<>();
        jdbc.sql("select " + COLUMNS + " from reporting_ticket_fact f where " + where(filter, drill, scope, p)
                        + " order by f.created_at desc, f.ticket_id desc limit :limit")
                .params(p).query(rs -> {
                    out.add(row(rs, n));
                });
        return out;
    }

    /** Период провала: точка графика внутри периода дашборда. */
    static ReportFilter narrow(ReportFilter filter, Drill drill) {
        if (drill.date() == null) {
            return filter;
        }
        LocalDate start = drill.date();
        LocalDate end = "week".equals(filter.granularity()) ? start.plusDays(6) : start;
        if (start.isBefore(filter.from())) {
            start = filter.from();
        }
        if (end.isAfter(filter.to())) {
            end = filter.to();
        }
        if (start.isAfter(end)) {
            throw ApiException.badRequest("invalid_date", "Дата вне периода дашборда");
        }
        return new ReportFilter(start, end, filter.zone(), filter.groupId(), filter.type(), filter.priority(),
                filter.serviceId(), filter.lang());
    }

    private String where(ReportFilter filter, Drill drill, ReportScope scope, Map<String, Object> p) {
        ReportFilter f = narrow(filter, drill);
        StringBuilder sql = new StringBuilder(f.where(scope, p));
        if (scope.allTickets() && !scope.drillAll()) {
            // Показатели всего арендатора (ADM), но заявки — только свои группы (AGT, LEAD).
            sql.append(ReportFilter.scopeSql(scope, p));
        }
        sql.append(" and not f.confidential and ").append(METRICS.get(drill.metric()));
        if (drill.metric().startsWith("breached") && !"breachedOpen".equals(drill.metric())) {
            if ("breachedResponse".equals(drill.metric())) {
                p.put("breachMetric", "response");
            } else if ("breachedResolution".equals(drill.metric())) {
                p.put("breachMetric", "resolution");
            }
        }
        if (drill.status() != null) {
            sql.append(" and f.status_category = :dStatus");
            p.put("dStatus", drill.status());
        }
        if (drill.priority() != null) {
            if (!ReportFilter.PRIORITIES.contains(drill.priority())) {
                throw ApiException.badRequest("invalid_priority", "Неизвестный приоритет");
            }
            sql.append(" and f.priority = :dPriority");
            p.put("dPriority", drill.priority());
        }
        if (drill.type() != null) {
            if (!ReportFilter.TYPES.contains(drill.type())) {
                throw ApiException.badRequest("invalid_type", "Неизвестный тип заявки");
            }
            sql.append(" and f.type = :dType");
            p.put("dType", drill.type());
        }
        if (drill.groupId() != null) {
            sql.append(idCondition("f.group_id", "dGroup", drill.groupId(), p));
        }
        if (drill.serviceId() != null) {
            sql.append(idCondition("f.service_id", "dService", drill.serviceId(), p));
        }
        if (drill.channel() != null) {
            if (ReportFilter.NONE.equals(drill.channel())) {
                sql.append(" and f.channel is null");
            } else {
                sql.append(" and f.channel = :dChannel");
                p.put("dChannel", drill.channel());
            }
        }
        if (drill.age() != null) {
            sql.append(" and (").append(Dashboards.AGE_SQL).append(") = :dAge");
            p.put("dAge", drill.age());
        }
        if (drill.score() != null) {
            sql.append(" and f.csat_score = :dScore");
            p.put("dScore", drill.score());
        }
        return sql.toString();
    }

    private static String idCondition(String column, String param, String value, Map<String, Object> p) {
        if (ReportFilter.NONE.equals(value)) {
            return " and " + column + " is null";
        }
        try {
            p.put(param, UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_id", "Некорректный идентификатор");
        }
        return " and " + column + " = :" + param;
    }

    private static String breach(String metric) {
        return "exists (select 1 from reporting_sla_breach b where b.ticket_id = f.ticket_id and b.kind = 'sla'"
                + " and b.breached_at >= :from and b.breached_at < :to"
                + (metric == null ? "" : " and b.metric = :breachMetric") + ")";
    }

    private static TicketRow row(java.sql.ResultSet rs, Names n) throws java.sql.SQLException {
        UUID group = rs.getObject("group_id", UUID.class);
        UUID service = rs.getObject("service_id", UUID.class);
        int csat = rs.getInt("csat_score");
        Integer score = rs.wasNull() ? null : csat;
        return new TicketRow(rs.getObject("ticket_id", UUID.class), rs.getString("number"), rs.getString("type"),
                rs.getString("title"), rs.getString("status_code"), rs.getString("status_category"),
                rs.getString("priority"), group, n.group(group), service, n.service(service),
                rs.getObject("assignee_id", UUID.class), instant(rs, "created_at"),
                instant(rs, "first_response_at"), instant(rs, "resolved_at"), rs.getBoolean("sla_breached"), score);
    }

    private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
