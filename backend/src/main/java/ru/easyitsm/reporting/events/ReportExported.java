package ru.easyitsm.reporting.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Пользователь выгрузил отчёт (RPT-04, SEC-29). Публикуется в транзакции записи журнала выгрузок модуля.
 *
 * @param exportId id записи журнала выгрузок
 * @param report   что выгружено: {@code dashboard:<id>} или {@code tickets}
 * @param format   csv, xlsx или pdf
 * @param filters  применённые фильтры (период, группа, тип, приоритет, услуга, параметры провала)
 * @param rowCount сколько строк данных попало в файл
 * @param reason   причина выгрузки сверх лимита строк; null, если лимит не превышен
 */
public record ReportExported(UUID tenantId, Instant occurredAt, UUID actorId, UUID exportId, String report,
                             String format, Map<String, String> filters, int rowCount, String reason) {
}
