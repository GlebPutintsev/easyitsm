package ru.easyitsm.reporting.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ru.easyitsm.reporting.internal.ReportViews.Column;
import ru.easyitsm.reporting.internal.ReportViews.Dashboard;
import ru.easyitsm.reporting.internal.ReportViews.TicketRow;
import ru.easyitsm.reporting.internal.ReportViews.Widget;

/** Документ выгрузки: заголовок, шапка с периодом и фильтрами, таблицы. Один на CSV, XLSX и PDF. */
record ExportModel(String title, String lang, List<String> header, List<Table> tables) {

    record Cell(Object value, String type) {
    }

    record Table(String title, List<String> columns, List<String> types, List<List<Cell>> rows) {
    }

    int rowCount() {
        return tables.stream().mapToInt(t -> t.rows().size()).sum();
    }

    static ExportModel of(Dashboard d, ReportFilter filter, String filtersText) {
        String lang = filter.lang();
        List<Table> tables = new ArrayList<>();
        for (Widget w : d.widgets()) {
            tables.add(table(w, lang));
        }
        return new ExportModel(d.title(), lang, header(filter, d.allTickets(), filtersText), tables);
    }

    static ExportModel tickets(List<TicketRow> rows, ReportFilter filter, boolean allTickets, String filtersText) {
        String lang = filter.lang();
        ZoneId zone = filter.zone();
        DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        List<String> cols = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (String c : List.of("number", "title", "type", "status", "priority", "group", "service", "createdAt",
                "firstResponseAt", "resolvedAt", "slaBreached", "score")) {
            cols.add(Labels.get(lang, "col." + c));
            types.add("score".equals(c) ? ReportViews.NUMBER : ReportViews.TEXT);
        }
        List<List<Cell>> out = new ArrayList<>();
        for (TicketRow r : rows) {
            out.add(List.of(
                    text(r.number()), text(r.title()),
                    text(Labels.get(lang, "type." + r.type())),
                    text(Labels.get(lang, "status." + r.statusCategory())),
                    text(Labels.get(lang, "priority." + r.priority())),
                    text(r.groupName()), text(r.serviceName()),
                    text(fmt(dt, r.createdAt())), text(fmt(dt, r.firstResponseAt())), text(fmt(dt, r.resolvedAt())),
                    text(Labels.get(lang, r.slaBreached() ? "file.yes" : "file.no")),
                    new Cell(r.csatScore() == null ? null : r.csatScore().longValue(), ReportViews.NUMBER)));
        }
        List<String> header = header(filter, allTickets, filtersText);
        header.add(Labels.get(lang, "file.confidential"));
        return new ExportModel(Labels.get(lang, "widget.tickets"), lang, header,
                List.of(new Table(Labels.get(lang, "widget.tickets"), cols, types, out)));
    }

    private static List<String> header(ReportFilter f, boolean allTickets, String filtersText) {
        String lang = f.lang();
        List<String> h = new ArrayList<>();
        h.add(Labels.get(lang, "file.period") + ": " + f.from() + " — " + f.to() + " (" + f.zone().getId() + ")");
        h.add(Labels.get(lang, "file.generated") + ": " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(f.zone()).format(Instant.now()));
        if (filtersText != null && !filtersText.isBlank()) {
            h.add(Labels.get(lang, "file.filters") + ": " + filtersText);
        }
        if (!allTickets) {
            h.add(Labels.get(lang, "file.scope.own"));
        }
        return h;
    }

    private static Table table(Widget w, String lang) {
        List<String> cols = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<List<Cell>> rows = new ArrayList<>();
        if ("kpi".equals(w.kind())) {
            cols.add(Labels.get(lang, "col.metric"));
            cols.add(Labels.get(lang, "col.value"));
            types.add(ReportViews.TEXT);
            types.add(ReportViews.TEXT);
            for (Map<String, Object> r : w.rows()) {
                rows.add(List.of(text((String) r.get("label")), new Cell(r.get("value"), (String) r.get("type"))));
            }
            return new Table(w.title(), cols, types, rows);
        }
        for (Column c : w.columns()) {
            cols.add(c.label() + ("duration".equals(c.type()) ? ", " + Labels.get(lang, "file.hours") : ""));
            types.add(c.type());
        }
        for (Map<String, Object> r : w.rows()) {
            List<Cell> cells = new ArrayList<>();
            for (int i = 0; i < w.columns().size(); i++) {
                Column c = w.columns().get(i);
                Object v = i == 0 && !"line".equals(w.kind()) ? r.get("label") : r.get(c.key());
                cells.add(new Cell(v, c.type()));
            }
            rows.add(cells);
        }
        return new Table(w.title(), cols, types, rows);
    }

    private static Cell text(String v) {
        return new Cell(v, ReportViews.TEXT);
    }

    private static String fmt(DateTimeFormatter f, Instant i) {
        return i == null ? "" : f.format(i);
    }

    /** Значение для CSV и PDF: числа по правилам языка, длительность — часами (CSV) или «2 ч 15 мин» (PDF). */
    static String display(Cell c, String lang, boolean human) {
        Object v = c.value();
        if (v == null) {
            return human ? "—" : "";
        }
        Locale locale = "en".equals(lang) ? Locale.ENGLISH : Locale.forLanguageTag("ru");
        return switch (c.type()) {
            case ReportViews.DURATION -> human ? duration(((Number) v).longValue(), lang)
                    : String.format(locale, "%.2f", ((Number) v).doubleValue() / 3600.0);
            case ReportViews.PERCENT -> String.format(locale, "%.1f", ((Number) v).doubleValue()) + (human ? " %" : "");
            case ReportViews.SCORE -> String.format(locale, "%.2f", ((Number) v).doubleValue());
            case ReportViews.NUMBER -> v instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(v);
            default -> String.valueOf(v);
        };
    }

    static String duration(long seconds, String lang) {
        long minutes = Math.round(seconds / 60.0);
        long days = minutes / (24 * 60);
        long hours = (minutes % (24 * 60)) / 60;
        long mins = minutes % 60;
        String d = Labels.get(lang, "file.days");
        String h = Labels.get(lang, "file.hours");
        String m = Labels.get(lang, "file.minutes");
        if (days > 0) {
            return days + " " + d + " " + hours + " " + h;
        }
        if (hours > 0) {
            return hours + " " + h + " " + mins + " " + m;
        }
        return mins + " " + m;
    }
}
