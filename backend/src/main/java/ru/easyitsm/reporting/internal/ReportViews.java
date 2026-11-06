package ru.easyitsm.reporting.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ответы API отчётов. Форма повторена в {@code frontend/src/features/reports/api.ts}. */
final class ReportViews {

    private ReportViews() {
    }

    /** Типы значений колонки: как показывать и выгружать число. */
    static final String TEXT = "text";
    static final String DATE = "date";
    static final String NUMBER = "number";
    /** Проценты 0–100 с одним знаком. */
    static final String PERCENT = "percent";
    /** Секунды; на экране «2 ч 15 мин», в CSV и XLSX — часы с десятыми. */
    static final String DURATION = "duration";
    /** Средняя оценка 1–5 с двумя знаками. */
    static final String SCORE = "score";

    /**
     * Колонка виджета.
     *
     * @param drill показатель провала по клику на значение этой колонки ({@code metric} запроса списка заявок)
     */
    record Column(String key, String label, String type, String drill) {
    }

    /**
     * Виджет дашборда. Любой виджет — таблица {@code columns × rows}, поэтому его можно выгрузить как есть.
     * <ul>
     *   <li>{@code kpi} — строки {key, label, value, type, drill};</li>
     *   <li>{@code line} — первая колонка {@code bucket} (дата начала дня или недели), остальные — ряды;</li>
     *   <li>{@code bar} — {key, label} и одна числовая колонка;</li>
     *   <li>{@code table} — {key, label} и показатели.</li>
     * </ul>
     * В строке {@code drill} — параметры списка заявок для этой строки (группа, статус, дата...); показатель
     * берётся из колонки, по значению которой кликнули.
     */
    record Widget(String id, String kind, String title, List<Column> columns, List<Map<String, Object>> rows) {
    }

    /**
     * @param allTickets   показатели по всем заявкам арендатора; false — только свои группы
     * @param canDrillDown пользователь может открыть список заявок по клику
     */
    record Dashboard(String id, String title, LocalDate from, LocalDate to, String timezone, String granularity,
                     boolean allTickets, boolean canDrillDown, Instant generatedAt, List<Widget> widgets) {
    }

    record DashboardRef(String id, String title) {
    }

    /** Строка списка заявок при провале. Конфиденциальные заявки в список не попадают. */
    record TicketRow(UUID id, String number, String type, String title, String statusCode, String statusCategory,
                     String priority, UUID groupId, String groupName, UUID serviceId, String serviceName,
                     UUID assigneeId, Instant createdAt, Instant firstResponseAt, Instant resolvedAt,
                     boolean slaBreached, Integer csatScore) {
    }

    /** Запись журнала выгрузок для SEC и AUD. */
    record ExportEntry(UUID id, String report, String format, Map<String, String> filters, int rowCount,
                       String reason, UUID createdBy, Instant createdAt) {
    }
}
