/**
 * События заявок. Публикуются в транзакции изменения и доставляются через outbox.
 * Каждое событие несёт снимок заявки после изменения ({@link ru.easyitsm.tickets.events.TicketEvent#ticket()})
 * и время бизнес-события {@code occurredAt}, поэтому слушателю не нужны обратные запросы.
 */
@org.springframework.modulith.NamedInterface("events")
package ru.easyitsm.tickets.events;
