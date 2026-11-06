/**
 * Изменения и CAB (CHG-01…12, раздел 5.6 ТЗ): тип и обязательные поля, план работ по шагам, анкета риска и маршрут
 * согласования, календарь, заморозки и конфликты, CAB, шаблоны стандартных изменений, CI/CD, экстренные изменения
 * с последующим согласованием, разбор и экспорт плана в XLSX, DOCX и PDF.
 * <p>
 * Изменение — заявка типа {@code change} модуля tickets (номер {@code CHG-…}, лента, связи). Модуль хранит свои
 * данные рядом и ведёт статус сам: переходы заявки выполняются через {@code TicketCommands.transition} от имени
 * системы. Контракт — {@code docs/api/changes-contract.md}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Changes")
package ru.easyitsm.changes;
