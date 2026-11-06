package ru.easyitsm.reporting.internal;

import java.util.Map;

/**
 * Подписи дашбордов, виджетов, колонок и кодов на русском и английском. Один источник для экрана и выгрузок:
 * экран показывает подписи из ответа API, файлы CSV, XLSX и PDF — те же самые.
 */
final class Labels {

    private static final Map<String, String[]> TEXT = Map.ofEntries(
            // Дашборды
            e("dashboard.overview", "Сводка", "Overview"),
            e("dashboard.queue", "Очередь", "Queue"),
            e("dashboard.sla", "SLA", "SLA"),
            e("dashboard.groups", "Группы", "Groups"),
            e("dashboard.services", "Услуги", "Services"),
            e("dashboard.satisfaction", "Удовлетворённость", "Satisfaction"),
            // Виджеты
            e("widget.overview.kpi", "Главные показатели", "Key metrics"),
            e("widget.overview.volume", "Поступило и решено", "Created and resolved"),
            e("widget.overview.byType", "Поступило по типам", "Created by type"),
            e("widget.overview.byChannel", "Поступило по каналам", "Created by channel"),
            e("widget.queue.kpi", "Сейчас в работе", "In progress now"),
            e("widget.queue.byStatus", "Открытые по статусам", "Open by status"),
            e("widget.queue.byPriority", "Открытые по приоритетам", "Open by priority"),
            e("widget.queue.byGroup", "Открытые по группам", "Open by group"),
            e("widget.queue.aging", "Возраст открытых заявок", "Open ticket age"),
            e("widget.sla.kpi", "Соблюдение SLA", "SLA compliance"),
            e("widget.sla.trend", "Нарушения SLA", "SLA breaches"),
            e("widget.sla.byPriority", "SLA по приоритетам", "SLA by priority"),
            e("widget.sla.byGroup", "SLA по группам", "SLA by group"),
            e("widget.groups.table", "Показатели групп", "Group metrics"),
            e("widget.services.table", "Показатели услуг", "Service metrics"),
            e("widget.satisfaction.kpi", "Оценки заявителей", "Requester ratings"),
            e("widget.satisfaction.distribution", "Распределение оценок", "Rating distribution"),
            e("widget.satisfaction.trend", "Средняя оценка", "Average rating"),
            e("widget.satisfaction.byGroup", "Оценки по группам", "Ratings by group"),
            e("widget.tickets", "Заявки", "Tickets"),
            // Показатели и колонки
            e("col.created", "Поступило", "Created"),
            e("col.resolved", "Решено", "Resolved"),
            e("col.backlog", "Открыто сейчас", "Open now"),
            e("col.open", "Открыто", "Open"),
            e("col.mtta", "Среднее время реакции", "Mean time to acknowledge"),
            e("col.mttr", "Среднее время решения", "Mean time to resolve"),
            e("col.slaCompliance", "Соблюдение SLA", "SLA compliance"),
            e("col.reopenRate", "Открыты повторно", "Reopen rate"),
            e("col.reopened", "Открыты повторно", "Reopened"),
            e("col.repeats", "Повторные обращения", "Repeat contacts"),
            e("col.csat", "Средняя оценка", "Average rating"),
            e("col.responses", "Оценок", "Ratings"),
            e("col.csatPercent", "Довольны (4–5)", "Satisfied (4–5)"),
            e("col.responseRate", "Доля оценённых", "Response rate"),
            e("col.unassigned", "Без исполнителя", "Unassigned"),
            e("col.overdue", "Просрочены", "Overdue"),
            e("col.pending", "В ожидании", "Pending"),
            e("col.breachedOpen", "С нарушением SLA", "SLA breached"),
            e("col.responseCompliance", "SLA реакции", "Response SLA"),
            e("col.resolutionCompliance", "SLA решения", "Resolution SLA"),
            e("col.breaches", "Нарушений SLA", "SLA breaches"),
            e("col.total", "С SLA", "With SLA"),
            e("col.met", "Выполнено", "Met"),
            e("col.breached", "Нарушено", "Breached"),
            e("col.responseBreaches", "Нарушения реакции", "Response breaches"),
            e("col.resolutionBreaches", "Нарушения решения", "Resolution breaches"),
            e("col.count", "Заявок", "Tickets"),
            e("col.group", "Группа", "Group"),
            e("col.service", "Услуга", "Service"),
            e("col.priority", "Приоритет", "Priority"),
            e("col.status", "Статус", "Status"),
            e("col.type", "Тип", "Type"),
            e("col.channel", "Канал", "Channel"),
            e("col.bucket.day", "День", "Day"),
            e("col.bucket.week", "Неделя", "Week"),
            e("col.score", "Оценка", "Rating"),
            e("col.age", "Возраст", "Age"),
            e("col.metric", "Показатель", "Metric"),
            e("col.value", "Значение", "Value"),
            e("col.number", "Номер", "Number"),
            e("col.title", "Тема", "Subject"),
            e("col.createdAt", "Создана", "Created"),
            e("col.firstResponseAt", "Первая реакция", "First response"),
            e("col.resolvedAt", "Решена", "Resolved"),
            e("col.slaBreached", "Нарушение SLA", "SLA breached"),
            // Коды
            e("status.draft", "Черновик", "Draft"),
            e("status.new", "Новая", "New"),
            e("status.approval", "Согласование", "Approval"),
            e("status.assigned", "Назначена", "Assigned"),
            e("status.in_progress", "В работе", "In progress"),
            e("status.pending", "Ожидание", "Pending"),
            e("status.resolved", "Решена", "Resolved"),
            e("status.closed", "Закрыта", "Closed"),
            e("status.cancelled", "Отменена", "Cancelled"),
            e("priority.P1", "P1 Критический", "P1 Critical"),
            e("priority.P2", "P2 Высокий", "P2 High"),
            e("priority.P3", "P3 Средний", "P3 Medium"),
            e("priority.P4", "P4 Низкий", "P4 Low"),
            e("type.incident", "Инциденты", "Incidents"),
            e("type.request", "Запросы", "Requests"),
            e("type.problem", "Проблемы", "Problems"),
            e("type.change", "Изменения", "Changes"),
            e("type.task", "Задачи", "Tasks"),
            e("channel.portal", "Портал", "Portal"),
            e("channel.email", "Почта", "Email"),
            e("channel.api", "API", "API"),
            e("channel.chat", "Чат", "Chat"),
            e("channel.monitoring", "Мониторинг", "Monitoring"),
            e("channel.agent", "Создана агентом", "Created by agent"),
            e("channel.none", "Не указан", "Not set"),
            e("age.lt1d", "До суток", "Under 1 day"),
            e("age.d1to3", "1–3 дня", "1–3 days"),
            e("age.d3to7", "3–7 дней", "3–7 days"),
            e("age.d7to30", "7–30 дней", "7–30 days"),
            e("age.gt30d", "Больше 30 дней", "Over 30 days"),
            e("group.none", "Без группы", "No group"),
            e("group.unknown", "Группа удалена", "Deleted group"),
            e("service.none", "Без услуги", "No service"),
            e("service.unknown", "Услуга вне каталога", "Service not in catalog"),
            e("score.1", "1 — очень плохо", "1 — very poor"),
            e("score.2", "2", "2"),
            e("score.3", "3", "3"),
            e("score.4", "4", "4"),
            e("score.5", "5 — отлично", "5 — excellent"),
            // Файлы выгрузки
            e("file.period", "Период", "Period"),
            e("file.generated", "Сформирован", "Generated"),
            e("file.filters", "Фильтры", "Filters"),
            e("file.scope.own", "Только заявки ваших групп", "Your groups only"),
            e("file.yes", "Да", "Yes"),
            e("file.no", "Нет", "No"),
            e("file.hours", "ч", "h"),
            e("file.minutes", "мин", "min"),
            e("file.days", "д", "d"),
            e("file.empty", "Нет данных за период", "No data for the period"),
            e("file.confidential", "Конфиденциальные заявки в список не входят",
                    "Confidential tickets are not listed"));

    private Labels() {
    }

    static String get(String lang, String key) {
        String[] v = TEXT.get(key);
        if (v == null) {
            return key;
        }
        return "en".equals(lang) ? v[1] : v[0];
    }

    static boolean has(String key) {
        return TEXT.containsKey(key);
    }

    private static Map.Entry<String, String[]> e(String key, String ru, String en) {
        return Map.entry(key, new String[] {ru, en});
    }
}
