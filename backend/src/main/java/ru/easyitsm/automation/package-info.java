/**
 * Автоматизация (AUT-01, AUT-04): правила «событие — условие — действие».
 * <p>
 * Правило слушает одно событие заявки или SLA, проверяет условия по снимку заявки и полям события и выполняет
 * действия через {@code TicketCommands} от имени системы. Каждый запуск с совпавшими условиями пишется в журнал,
 * в том числе остановленный защитой от петель: глубина цепочки не больше 5, правило не запускает само себя через
 * цепочку, одно правило на одной заявке — не чаще порога за окно времени.
 * <p>
 * Своих событий и Java API у модуля нет: он только слушает {@code tickets.events} и {@code sla.events}.
 * REST: {@code /api/v1/automation/rules}, {@code /api/v1/automation/test}, {@code /api/v1/automation/runs}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Automation")
package ru.easyitsm.automation;
