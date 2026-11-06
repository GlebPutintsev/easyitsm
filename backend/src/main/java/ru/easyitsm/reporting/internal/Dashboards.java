package ru.easyitsm.reporting.internal;

import static ru.easyitsm.reporting.internal.ReportViews.DATE;
import static ru.easyitsm.reporting.internal.ReportViews.DURATION;
import static ru.easyitsm.reporting.internal.ReportViews.NUMBER;
import static ru.easyitsm.reporting.internal.ReportViews.PERCENT;
import static ru.easyitsm.reporting.internal.ReportViews.SCORE;
import static ru.easyitsm.reporting.internal.ReportViews.TEXT;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.reporting.internal.NameBook.Names;
import ru.easyitsm.reporting.internal.ReportViews.Column;
import ru.easyitsm.reporting.internal.ReportViews.Dashboard;
import ru.easyitsm.reporting.internal.ReportViews.DashboardRef;
import ru.easyitsm.reporting.internal.ReportViews.Widget;

/**
 * Готовые дашборды RPT-01 и 10.15: сводка, очередь, SLA, группы, услуги, удовлетворённость. Все показатели
 * считаются по витрине {@code reporting_ticket_fact} с фильтрами периода и области пользователя.
 * <p>
 * Определения:
 * <ul>
 *   <li>MTTA — среднее календарное время от создания до первой реакции по заявкам, получившим реакцию в периоде;</li>
 *   <li>MTTR — среднее календарное время от создания до решения по заявкам, решённым в периоде;</li>
 *   <li>соблюдение SLA — доля заявок с SLA, решённых в периоде без нарушения реакции и решения;</li>
 *   <li>повторное открытие — решённая в периоде заявка, которую хотя бы раз возвращали из «Решена»;</li>
 *   <li>повторное обращение — заявка того же заявителя по той же услуге или категории в течение 7 дней после
 *       предыдущей;</li>
 *   <li>удовлетворённость — оценки 1–5, поставленные в периоде; «довольны» — 4 и 5.</li>
 * </ul>
 */
@Service
class Dashboards {

    static final List<String> IDS = List.of("overview", "queue", "sla", "groups", "services", "satisfaction");

    static final String ACTIVE = "f.status_category in ('new', 'approval', 'assigned', 'in_progress', 'pending')";
    static final List<String> ACTIVE_ORDER = List.of("new", "approval", "assigned", "in_progress", "pending");
    static final List<String> PRIORITIES = List.of("P1", "P2", "P3", "P4");
    static final List<String> AGES = List.of("lt1d", "d1to3", "d3to7", "d7to30", "gt30d");
    static final String AGE_SQL = """
            case when f.created_at > cast(:now as timestamptz) - interval '1 day' then 'lt1d'
                 when f.created_at > cast(:now as timestamptz) - interval '3 days' then 'd1to3'
                 when f.created_at > cast(:now as timestamptz) - interval '7 days' then 'd3to7'
                 when f.created_at > cast(:now as timestamptz) - interval '30 days' then 'd7to30'
                 else 'gt30d' end""";
    /** Повторное обращение: тот же заявитель, та же услуга или категория, предыдущая заявка не старше 7 дней. */
    static final String REPEAT = """
            exists (select 1 from reporting_ticket_fact p
                    where p.requester_id = f.requester_id and p.ticket_id <> f.ticket_id
                      and p.created_at < f.created_at and p.created_at >= f.created_at - interval '7 days'
                      and ((f.service_id is not null and p.service_id = f.service_id)
                           or (f.category_id is not null and p.category_id = f.category_id)))""";

    private final JdbcClient jdbc;
    private final NameBook names;

    Dashboards(JdbcClient jdbc, NameBook names) {
        this.jdbc = jdbc;
        this.names = names;
    }

    List<DashboardRef> list(String lang) {
        return IDS.stream().map(id -> new DashboardRef(id, Labels.get(lang, "dashboard." + id))).toList();
    }

    @Transactional(readOnly = true)
    Dashboard build(String id, ReportFilter filter, ReportScope scope) {
        Names n = names.load(filter.lang());
        Ctx c = new Ctx(filter, scope, n);
        List<Widget> widgets = switch (id) {
            case "overview" -> overview(c);
            case "queue" -> queue(c);
            case "sla" -> sla(c);
            case "groups" -> groups(c);
            case "services" -> services(c);
            case "satisfaction" -> satisfaction(c);
            default -> throw ApiException.notFound("Дашборд");
        };
        return new Dashboard(id, Labels.get(filter.lang(), "dashboard." + id), filter.from(), filter.to(),
                filter.zone().getId(), filter.granularity(), scope.allTickets(), scope.drill(), Instant.now(),
                widgets);
    }

    // ---------------------------------------------------------------- дашборды

    private List<Widget> overview(Ctx c) {
        Metrics m = c.total();
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(c.kpi("created", NUMBER, m.created, "created"));
        kpi.add(c.kpi("resolved", NUMBER, m.resolved, "resolved"));
        kpi.add(c.kpi("backlog", NUMBER, m.open, "open"));
        kpi.add(c.kpi("mtta", DURATION, m.mtta, "responded"));
        kpi.add(c.kpi("mttr", DURATION, m.mttr, "resolved"));
        kpi.add(c.kpi("slaCompliance", PERCENT, m.slaCompliance(), "slaMissed"));
        kpi.add(c.kpi("reopenRate", PERCENT, pct(m.reopened, m.resolved), "reopened"));
        kpi.add(c.kpi("repeats", NUMBER, m.repeats, "repeat"));
        kpi.add(c.kpi("csat", SCORE, m.csat, "rated"));

        Map<LocalDate, Long> created = c.countByBucket("f.created_at", "true");
        Map<LocalDate, Long> resolved = c.countByBucket("f.resolved_at", "true");
        List<Map<String, Object>> volume = new ArrayList<>();
        for (LocalDate b : c.buckets()) {
            volume.add(row(b.toString(), null, Map.of("date", b.toString()), "bucket", b.toString(),
                    "created", created.getOrDefault(b, 0L), "resolved", resolved.getOrDefault(b, 0L)));
        }

        List<Map<String, Object>> byType = new ArrayList<>();
        c.countBy("f.type", "f.created_at >= :from and f.created_at < :to").forEach((code, count) ->
                byType.add(row(code, Labels.get(c.lang(), "type." + code), Map.of("type", code), "count", count)));
        sortDesc(byType);
        List<Map<String, Object>> byChannel = new ArrayList<>();
        c.countBy("coalesce(f.channel, 'none')", "f.created_at >= :from and f.created_at < :to")
                .forEach((code, count) -> byChannel.add(row(code, Labels.get(c.lang(), "channel." + code),
                        Map.of("channel", code), "count", count)));
        sortDesc(byChannel);

        return List.of(
                c.kpiWidget("overview.kpi", kpi),
                new Widget("overview.volume", "line", c.title("overview.volume"), List.of(
                        c.bucketColumn(), c.col("created", NUMBER, "created"), c.col("resolved", NUMBER, "resolved")),
                        volume),
                c.bar("overview.byType", "type", "created", byType),
                c.bar("overview.byChannel", "channel", "created", byChannel));
    }

    private List<Widget> queue(Ctx c) {
        Metrics m = c.total();
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(c.kpi("open", NUMBER, m.open, "open"));
        kpi.add(c.kpi("unassigned", NUMBER, m.unassigned, "unassigned"));
        kpi.add(c.kpi("overdue", NUMBER, m.overdue, "overdue"));
        kpi.add(c.kpi("breachedOpen", NUMBER, m.breachedOpen, "breachedOpen"));
        Map<String, Object> pending = c.kpi("pending", NUMBER, m.pending, "open");
        pending.put("drill", Map.of("metric", "open", "status", "pending"));
        kpi.add(pending);

        Map<String, Long> byStatusCounts = c.countBy("f.status_category", ACTIVE);
        List<Map<String, Object>> byStatus = new ArrayList<>();
        for (String s : ACTIVE_ORDER) {
            long count = byStatusCounts.getOrDefault(s, 0L);
            if (count > 0 || !"approval".equals(s)) {
                byStatus.add(row(s, Labels.get(c.lang(), "status." + s), Map.of("status", s), "count", count));
            }
        }
        Map<String, Long> byPriorityCounts = c.countBy("f.priority", ACTIVE);
        List<Map<String, Object>> byPriority = new ArrayList<>();
        for (String p : PRIORITIES) {
            byPriority.add(row(p, Labels.get(c.lang(), "priority." + p), Map.of("priority", p),
                    "count", byPriorityCounts.getOrDefault(p, 0L)));
        }
        List<Map<String, Object>> byGroup = new ArrayList<>();
        c.countBy("cast(f.group_id as text)", ACTIVE).forEach((g, count) -> byGroup.add(
                row(key(g), c.names().group(uuid(g)), Map.of("groupId", key(g)), "count", count)));
        sortDesc(byGroup);
        Map<String, Long> ageCounts = c.countBy(AGE_SQL, ACTIVE);
        List<Map<String, Object>> aging = new ArrayList<>();
        for (String a : AGES) {
            aging.add(row(a, Labels.get(c.lang(), "age." + a), Map.of("age", a), "count", ageCounts.getOrDefault(a, 0L)));
        }
        return List.of(
                c.kpiWidget("queue.kpi", kpi),
                c.bar("queue.byStatus", "status", "open", byStatus),
                c.bar("queue.byPriority", "priority", "open", byPriority),
                c.bar("queue.byGroup", "group", "open", byGroup),
                c.bar("queue.aging", "age", "open", aging));
    }

    private List<Widget> sla(Ctx c) {
        Metrics m = c.total();
        long breaches = c.breachCount(null);
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(c.kpi("slaCompliance", PERCENT, m.slaCompliance(), "slaMissed"));
        kpi.add(c.kpi("responseCompliance", PERCENT, pct(m.respMet, m.respTotal), "responseMissed"));
        kpi.add(c.kpi("resolutionCompliance", PERCENT, pct(m.resMet, m.slaTotal), "resolutionMissed"));
        kpi.add(c.kpi("breaches", NUMBER, breaches, "breached"));

        Map<LocalDate, Long> resp = c.breachesByBucket("response");
        Map<LocalDate, Long> res = c.breachesByBucket("resolution");
        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate b : c.buckets()) {
            trend.add(row(b.toString(), null, Map.of("date", b.toString()), "bucket", b.toString(),
                    "responseBreaches", resp.getOrDefault(b, 0L), "resolutionBreaches", res.getOrDefault(b, 0L)));
        }

        Map<String, Metrics> byPriority = c.metricsBy("f.priority");
        List<Map<String, Object>> priorityRows = new ArrayList<>();
        for (String p : PRIORITIES) {
            Metrics pm = byPriority.getOrDefault(p, Metrics.EMPTY);
            priorityRows.add(slaRow(p, Labels.get(c.lang(), "priority." + p), Map.of("priority", p), pm));
        }
        List<Map<String, Object>> groupRows = new ArrayList<>();
        c.metricsBy("cast(f.group_id as text)").forEach((g, gm) -> {
            if (gm.slaTotal > 0) {
                groupRows.add(slaRow(key(g), c.names().group(uuid(g)), Map.of("groupId", key(g)), gm));
            }
        });
        groupRows.sort(Comparator.comparing((Map<String, Object> r) -> (Long) r.get("breached")).reversed()
                .thenComparing(r -> (String) r.get("label")));

        List<Column> slaColumns = List.of(c.col("total", NUMBER, "slaResolved"), c.col("met", NUMBER, null),
                c.col("breached", NUMBER, "slaMissed"), c.col("slaCompliance", PERCENT, null));
        return List.of(
                c.kpiWidget("sla.kpi", kpi),
                new Widget("sla.trend", "line", c.title("sla.trend"), List.of(c.bucketColumn(),
                        c.col("responseBreaches", NUMBER, "breachedResponse"),
                        c.col("resolutionBreaches", NUMBER, "breachedResolution")), trend),
                c.table("sla.byPriority", "priority", slaColumns, priorityRows),
                c.table("sla.byGroup", "group", slaColumns, groupRows));
    }

    private List<Widget> groups(Ctx c) {
        List<Map<String, Object>> rows = new ArrayList<>();
        c.metricsBy("cast(f.group_id as text)").forEach((g, m) -> {
            if (m.isEmpty()) {
                return;
            }
            Map<String, Object> r = row(key(g), c.names().group(uuid(g)), Map.of("groupId", key(g)));
            r.put("created", m.created);
            r.put("resolved", m.resolved);
            r.put("open", m.open);
            r.put("mtta", m.mtta);
            r.put("mttr", m.mttr);
            r.put("slaCompliance", m.slaCompliance());
            r.put("reopened", m.reopened);
            r.put("csat", m.csat);
            rows.add(r);
        });
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (Long) r.get("created")).reversed()
                .thenComparing(r -> (String) r.get("label")));
        return List.of(c.table("groups.table", "group", List.of(
                c.col("created", NUMBER, "created"), c.col("resolved", NUMBER, "resolved"),
                c.col("open", NUMBER, "open"), c.col("mtta", DURATION, "responded"),
                c.col("mttr", DURATION, "resolved"), c.col("slaCompliance", PERCENT, "slaMissed"),
                c.col("reopened", NUMBER, "reopened"), c.col("csat", SCORE, "rated")), rows));
    }

    private List<Widget> services(Ctx c) {
        List<Map<String, Object>> rows = new ArrayList<>();
        c.metricsBy("cast(f.service_id as text)").forEach((s, m) -> {
            if (m.isEmpty()) {
                return;
            }
            Map<String, Object> r = row(key(s), c.names().service(uuid(s)), Map.of("serviceId", key(s)));
            r.put("created", m.created);
            r.put("resolved", m.resolved);
            r.put("open", m.open);
            r.put("mttr", m.mttr);
            r.put("slaCompliance", m.slaCompliance());
            r.put("repeats", m.repeats);
            r.put("csat", m.csat);
            rows.add(r);
        });
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (Long) r.get("created")).reversed()
                .thenComparing(r -> (String) r.get("label")));
        return List.of(c.table("services.table", "service", List.of(
                c.col("created", NUMBER, "created"), c.col("resolved", NUMBER, "resolved"),
                c.col("open", NUMBER, "open"), c.col("mttr", DURATION, "resolved"),
                c.col("slaCompliance", PERCENT, "slaMissed"), c.col("repeats", NUMBER, "repeat"),
                c.col("csat", SCORE, "rated")), rows));
    }

    private List<Widget> satisfaction(Ctx c) {
        Metrics m = c.total();
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(c.kpi("csat", SCORE, m.csat, "rated"));
        kpi.add(c.kpi("responses", NUMBER, m.csatCount, "rated"));
        kpi.add(c.kpi("csatPercent", PERCENT, pct(m.csatHappy, m.csatCount), null));
        kpi.add(c.kpi("responseRate", PERCENT, pct(m.csatCount, m.closed), null));

        Map<String, Long> scores = c.countBy("cast(f.csat_score as text)",
                "f.csat_at >= :from and f.csat_at < :to");
        List<Map<String, Object>> distribution = new ArrayList<>();
        for (int s = 5; s >= 1; s--) {
            String k = String.valueOf(s);
            distribution.add(row(k, Labels.get(c.lang(), "score." + k), Map.of("score", k),
                    "count", scores.getOrDefault(k, 0L)));
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        Map<LocalDate, double[]> byBucket = c.csatByBucket();
        for (LocalDate b : c.buckets()) {
            double[] v = byBucket.get(b);
            trend.add(row(b.toString(), null, Map.of("date", b.toString()), "bucket", b.toString(),
                    "csat", v == null ? null : round2(v[0]), "responses", v == null ? 0L : (long) v[1]));
        }

        List<Map<String, Object>> groupRows = new ArrayList<>();
        c.metricsBy("cast(f.group_id as text)").forEach((g, gm) -> {
            if (gm.csatCount == 0) {
                return;
            }
            Map<String, Object> r = row(key(g), c.names().group(uuid(g)), Map.of("groupId", key(g)));
            r.put("responses", gm.csatCount);
            r.put("csat", gm.csat);
            r.put("csatPercent", pct(gm.csatHappy, gm.csatCount));
            groupRows.add(r);
        });
        groupRows.sort(Comparator.comparing((Map<String, Object> r) -> (Long) r.get("responses")).reversed()
                .thenComparing(r -> (String) r.get("label")));

        return List.of(
                c.kpiWidget("satisfaction.kpi", kpi),
                c.bar("satisfaction.distribution", "score", "rated", distribution),
                new Widget("satisfaction.trend", "line", c.title("satisfaction.trend"), List.of(c.bucketColumn(),
                        c.col("csat", SCORE, "rated"), c.col("responses", NUMBER, "rated")), trend),
                c.table("satisfaction.byGroup", "group", List.of(c.col("responses", NUMBER, "rated"),
                        c.col("csat", SCORE, null), c.col("csatPercent", PERCENT, null)), groupRows));
    }

    private static Map<String, Object> slaRow(String key, String label, Map<String, String> drill, Metrics m) {
        Map<String, Object> r = row(key, label, drill);
        r.put("total", m.slaTotal);
        r.put("met", m.slaMet);
        r.put("breached", m.slaTotal - m.slaMet);
        r.put("slaCompliance", m.slaCompliance());
        return r;
    }

    // ---------------------------------------------------------------- строки

    static Map<String, Object> row(String key, String label, Map<String, String> drill, Object... values) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        if (label != null) {
            r.put("label", label);
        }
        for (int i = 0; i + 1 < values.length; i += 2) {
            r.put((String) values[i], values[i + 1]);
        }
        r.put("drill", drill);
        return r;
    }

    private static void sortDesc(List<Map<String, Object>> rows) {
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (Long) r.get("count")).reversed()
                .thenComparing(r -> String.valueOf(r.get("label"))));
    }

    static Double pct(long part, long total) {
        return total == 0 ? null : Math.round(part * 1000.0 / total) / 10.0;
    }

    static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }

    private static String key(String dim) {
        return dim == null ? ReportFilter.NONE : dim;
    }

    private static UUID uuid(String dim) {
        return dim == null ? null : UUID.fromString(dim);
    }

    // ---------------------------------------------------------------- показатели

    /** Показатели группы строк витрины за период (одна выборка с FILTER на каждый показатель). */
    record Metrics(long created, long resolved, long open, Long mtta, Long mttr, long slaTotal, long slaMet,
                   long resMet, long respTotal, long respMet, long reopened, long repeats, Double csat,
                   long csatCount, long csatHappy, long closed, long unassigned, long overdue, long breachedOpen,
                   long pending) {

        static final Metrics EMPTY = new Metrics(0, 0, 0, null, null, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0);

        Double slaCompliance() {
            return pct(slaMet, slaTotal);
        }

        boolean isEmpty() {
            return created == 0 && resolved == 0 && open == 0 && csatCount == 0 && respTotal == 0;
        }

        static Metrics read(ResultSet rs) throws SQLException {
            return new Metrics(rs.getLong("created"), rs.getLong("resolved"), rs.getLong("open"),
                    seconds(rs, "mtta"), seconds(rs, "mttr"), rs.getLong("sla_total"), rs.getLong("sla_met"),
                    rs.getLong("res_met"), rs.getLong("resp_total"), rs.getLong("resp_met"),
                    rs.getLong("reopened"), rs.getLong("repeats"), round2(nullableDouble(rs, "csat")),
                    rs.getLong("csat_count"), rs.getLong("csat_happy"), rs.getLong("closed"),
                    rs.getLong("unassigned"), rs.getLong("overdue"), rs.getLong("breached_open"),
                    rs.getLong("pending"));
        }

        private static Long seconds(ResultSet rs, String col) throws SQLException {
            Double v = nullableDouble(rs, col);
            return v == null ? null : Math.round(v);
        }

        private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
            double v = rs.getDouble(col);
            return rs.wasNull() ? null : v;
        }
    }

    static final String METRICS_SQL = """
            count(*) filter (where f.created_at >= :from and f.created_at < :to) as created,
            count(*) filter (where f.resolved_at >= :from and f.resolved_at < :to) as resolved,
            count(*) filter (where %1$s) as open,
            avg(extract(epoch from f.first_response_at - f.created_at))
                filter (where f.first_response_at >= :from and f.first_response_at < :to) as mtta,
            avg(extract(epoch from f.resolved_at - f.created_at))
                filter (where f.resolved_at >= :from and f.resolved_at < :to) as mttr,
            count(*) filter (where f.has_sla and f.resolved_at >= :from and f.resolved_at < :to) as sla_total,
            count(*) filter (where f.has_sla and f.resolved_at >= :from and f.resolved_at < :to
                and f.response_breached_at is null and f.resolution_breached_at is null) as sla_met,
            count(*) filter (where f.has_sla and f.resolved_at >= :from and f.resolved_at < :to
                and f.resolution_breached_at is null) as res_met,
            count(*) filter (where f.has_sla and f.first_response_at >= :from and f.first_response_at < :to)
                as resp_total,
            count(*) filter (where f.has_sla and f.first_response_at >= :from and f.first_response_at < :to
                and f.response_breached_at is null) as resp_met,
            count(*) filter (where f.resolved_at >= :from and f.resolved_at < :to and f.reopened_count > 0)
                as reopened,
            count(*) filter (where f.created_at >= :from and f.created_at < :to and %2$s) as repeats,
            avg(f.csat_score) filter (where f.csat_at >= :from and f.csat_at < :to) as csat,
            count(*) filter (where f.csat_at >= :from and f.csat_at < :to) as csat_count,
            count(*) filter (where f.csat_at >= :from and f.csat_at < :to and f.csat_score >= 4) as csat_happy,
            count(*) filter (where f.closed_at >= :from and f.closed_at < :to) as closed,
            count(*) filter (where %1$s and f.assignee_id is null) as unassigned,
            count(*) filter (where %1$s and f.due_at < :now) as overdue,
            count(*) filter (where %1$s and (f.response_breached_at is not null
                or f.resolution_breached_at is not null)) as breached_open,
            count(*) filter (where f.status_category = 'pending') as pending
            """.formatted(ACTIVE, REPEAT);

    /** Контекст построения одного дашборда: фильтры, область, имена, подписи. */
    private final class Ctx {

        private final ReportFilter filter;
        private final ReportScope scope;
        private final Names names;

        Ctx(ReportFilter filter, ReportScope scope, Names names) {
            this.filter = filter;
            this.scope = scope;
            this.names = names;
        }

        String lang() {
            return filter.lang();
        }

        Names names() {
            return names;
        }

        String title(String widget) {
            return Labels.get(lang(), "widget." + widget);
        }

        Column col(String key, String type, String drill) {
            return new Column(key, Labels.get(lang(), "col." + key), type, drill);
        }

        Column bucketColumn() {
            return new Column("bucket", Labels.get(lang(), "col.bucket." + filter.granularity()), DATE, null);
        }

        Map<String, Object> kpi(String key, String type, Object value, String drill) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", key);
            r.put("label", Labels.get(lang(), "col." + key));
            r.put("value", value);
            r.put("type", type);
            r.put("drill", drill == null ? null : Map.of("metric", drill));
            return r;
        }

        Widget kpiWidget(String id, List<Map<String, Object>> rows) {
            return new Widget(id, "kpi", title(id), List.of(new Column("label", Labels.get(lang(), "col.metric"),
                    TEXT, null), new Column("value", Labels.get(lang(), "col.value"), NUMBER, null)), rows);
        }

        Widget bar(String id, String dimension, String drill, List<Map<String, Object>> rows) {
            return new Widget(id, "bar", title(id), List.of(col(dimension, TEXT, null),
                    col("count", NUMBER, drill)), rows);
        }

        Widget table(String id, String dimension, List<Column> metrics, List<Map<String, Object>> rows) {
            List<Column> columns = new ArrayList<>();
            columns.add(col(dimension, TEXT, null));
            columns.addAll(metrics);
            return new Widget(id, "table", title(id), columns, rows);
        }

        String where(Map<String, Object> params) {
            return filter.where(scope, params);
        }

        Metrics total() {
            Map<String, Object> p = ReportFilter.params();
            String w = where(p);
            return jdbc.sql("select " + METRICS_SQL + " from reporting_ticket_fact f where " + w)
                    .params(p).query((rs, i) -> Metrics.read(rs)).single();
        }

        /** Показатели по измерению; ключ null — строки без значения (без группы, без услуги). */
        Map<String, Metrics> metricsBy(String dimension) {
            Map<String, Object> p = ReportFilter.params();
            String w = where(p);
            Map<String, Metrics> out = new LinkedHashMap<>();
            jdbc.sql("select " + dimension + " as dim, " + METRICS_SQL + " from reporting_ticket_fact f where " + w
                            + " group by 1")
                    .params(p).query(rs -> {
                        out.put(rs.getString("dim"), Metrics.read(rs));
                    });
            return out;
        }

        Map<String, Long> countBy(String dimension, String condition) {
            Map<String, Object> p = ReportFilter.params();
            String w = where(p);
            Map<String, Long> out = new LinkedHashMap<>();
            jdbc.sql("select " + dimension + " as dim, count(*) as n from reporting_ticket_fact f where " + w
                            + " and " + condition + " group by 1")
                    .params(p).query(rs -> {
                        out.put(rs.getString("dim"), rs.getLong("n"));
                    });
            return out;
        }

        Map<LocalDate, Long> countByBucket(String timeColumn, String condition) {
            Map<String, Object> p = ReportFilter.params();
            String w = where(p);
            p.put("unit", filter.granularity());
            return bucketed("select date_trunc(cast(:unit as text), " + timeColumn + " at time zone cast(:tz as text))"
                    + "::date as b, count(*) as n from reporting_ticket_fact f where " + w + " and " + condition
                    + " and " + timeColumn + " >= :from and " + timeColumn + " < :to group by 1", p, rs -> rs.getLong("n"));
        }

        long breachCount(String metric) {
            Map<String, Object> p = ReportFilter.params();
            String w = where(p);
            String m = metric == null ? "" : " and b.metric = :metric";
            if (metric != null) {
                p.put("metric", metric);
            }
            return jdbc.sql("select count(*) from reporting_sla_breach b join reporting_ticket_fact f"
                            + " on f.ticket_id = b.ticket_id where b.kind = 'sla' and b.breached_at >= :from"
                            + " and b.breached_at < :to" + m + " and " + w)
                    .params(p).query(Long.class).single();
        }

        Map<LocalDate, Long> breachesByBucket(String metric) {
            Map<String, Object> p = ReportFilter.params();
            String w = where(p);
            p.put("unit", filter.granularity());
            p.put("metric", metric);
            return bucketed("select date_trunc(cast(:unit as text), b.breached_at at time zone cast(:tz as text))::date"
                    + " as b, count(*) as n from reporting_sla_breach b join reporting_ticket_fact f"
                    + " on f.ticket_id = b.ticket_id where b.kind = 'sla' and b.metric = :metric"
                    + " and b.breached_at >= :from and b.breached_at < :to and " + w + " group by 1", p,
                    rs -> rs.getLong("n"));
        }

        /** Средняя оценка и число оценок по точкам графика. */
        Map<LocalDate, double[]> csatByBucket() {
            Map<String, Object> p = ReportFilter.params();
            String w = where(p);
            p.put("unit", filter.granularity());
            return bucketed("select date_trunc(cast(:unit as text), f.csat_at at time zone cast(:tz as text))::date"
                    + " as b, avg(f.csat_score) as a, count(*) as n from reporting_ticket_fact f where " + w
                    + " and f.csat_at >= :from and f.csat_at < :to group by 1", p,
                    rs -> new double[] {rs.getDouble("a"), rs.getLong("n")});
        }

        private <T> Map<LocalDate, T> bucketed(String sql, Map<String, Object> p, RowValue<T> value) {
            Map<LocalDate, T> out = new LinkedHashMap<>();
            jdbc.sql(sql).params(p).query(rs -> {
                out.put(rs.getObject("b", LocalDate.class), value.read(rs));
            });
            return out;
        }

        /** Точки графика: дни периода или недели с понедельника. */
        List<LocalDate> buckets() {
            List<LocalDate> out = new ArrayList<>();
            boolean weekly = "week".equals(filter.granularity());
            LocalDate b = weekly ? filter.from().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : filter.from();
            while (!b.isAfter(filter.to())) {
                out.add(b);
                b = weekly ? b.plusWeeks(1) : b.plusDays(1);
            }
            return out;
        }
    }

    @FunctionalInterface
    private interface RowValue<T> {
        T read(ResultSet rs) throws SQLException;
    }
}
