/**
 * Ядро заявок (TCK) и инциденты (INC): единая заявка всех типов, нумерация, лента, связи, подзадачи,
 * наблюдатели, настраиваемые поля, жизненные циклы, матрица приоритета, маршрутизация, массовые операции,
 * слияние и обнаружение секретов.
 * <p>
 * Контракт для других модулей:
 * <ul>
 *   <li>{@code api} — {@link ru.easyitsm.tickets.api.TicketCommands}, {@link ru.easyitsm.tickets.api.TicketQueries},
 *       {@link ru.easyitsm.tickets.api.SecretScanner} и их типы;</li>
 *   <li>{@code events} — события заявок, каждое несёт полный снимок {@link ru.easyitsm.tickets.api.TicketSnapshot};</li>
 *   <li>REST: {@code /api/v1/tickets/**} и {@code /api/v1/ticket-settings/**}, спецификация в {@code docs/api/tickets.yaml}.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Tickets")
package ru.easyitsm.tickets;
