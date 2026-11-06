package ru.easyitsm.audit.api;

/**
 * Запись в журнал аудита из других модулей (SEC-29). Вход, роли, отказ в доступе, опасные действия и изменение
 * настроек журнал записывает сам; модулю нужен этот интерфейс для того, что видно только ему: чтение
 * чувствительных данных, массовая выгрузка, ИИ-действия.
 * <p>
 * Запись делается в текущей транзакции: если действие откатилось, записи нет. Цепочка хешей арендатора
 * блокируется до конца транзакции, поэтому вызывайте в конце действия, а не в начале долгой операции.
 * Без арендатора (вне запроса и {@code TenantContext.runAs}) запись невозможна: {@link IllegalStateException}.
 */
public interface AuditLog {

    void record(AuditEntry entry);

    /**
     * Массовая выгрузка (SEC-14). Причину проверяет {@link BulkExport#requireReason}, право — {@link BulkExport#ROLES}
     * на контроллере вместе с повторной аутентификацией.
     *
     * @param dataset что выгружено: {@code tickets}, {@code audit}, {@code users}
     * @param rows    сколько строк ушло
     * @param filter  условия выборки, без персональных данных
     */
    default void exported(String dataset, String reason, long rows, java.util.Map<String, ?> filter) {
        AuditEntry entry = AuditEntry.of(AuditCategory.DATA, "data.export")
                .object("dataset", dataset)
                .detail("reason", BulkExport.requireReason(reason))
                .detail("rows", rows);
        if (filter != null) {
            filter.forEach((k, v) -> entry.detail("filter." + k, v));
        }
        record(entry);
    }
}
