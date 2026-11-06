/**
 * Журнал аудита и безопасность (SEC-13, SEC-14, SEC-29…SEC-32, угроза T-18).
 * <p>
 * Что пишется в журнал:
 * <ul>
 *   <li>вход, отказ во входе, блокировка, изменения факторов, роли и группы — по событиям модуля identity;</li>
 *   <li>отказ в доступе: ответ 403 или 404 на запрос к записи (AT-01, AT-05);</li>
 *   <li>действия с повторной аутентификацией ({@link ru.easyitsm.identity.api.RequiresRecentAuth}) и изменение
 *       настроек — по правилам маршрутов {@code AuditRoutes}, без правок в других модулях;</li>
 *   <li>явные записи модулей через {@link ru.easyitsm.audit.api.AuditLog}: чтение чувствительных данных,
 *       массовая выгрузка ({@link ru.easyitsm.audit.api.BulkExport}).</li>
 * </ul>
 * Записи защищены цепочкой хешей в PostgreSQL, недоступны приложению для изменения, копируются в SIEM
 * (Syslog TLS, CEF или JSON) с повтором при сбое. Пароли, токены и секреты в журнал не попадают:
 * детали проходят через {@link ru.easyitsm.audit.api.Masking}.
 * <p>
 * Модуль зависит только от identity. Другие модули могут пользоваться {@code audit.api}, а identity — нет:
 * его действия журнал получает из событий.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit and security")
package ru.easyitsm.audit;
