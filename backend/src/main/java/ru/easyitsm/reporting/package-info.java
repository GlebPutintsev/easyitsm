/**
 * Отчёты и дашборды (RPT-01, RPT-04): готовые дашборды «Сводка», «Очередь», «SLA», «Группы», «Услуги»,
 * «Удовлетворённость», провал в заявки в пределах прав и выгрузка в CSV, XLSX и PDF с защитой от формул и
 * записью в журнал.
 * <p>
 * Модуль не читает таблицы других модулей: собственная витрина {@code reporting_ticket_fact} строится из событий
 * {@code tickets.events}, {@code sla.events} и {@code surveys.events}. Справочные имена групп и услуг берутся
 * через {@code identity.api} и {@code catalog.api}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Reporting")
package ru.easyitsm.reporting;
