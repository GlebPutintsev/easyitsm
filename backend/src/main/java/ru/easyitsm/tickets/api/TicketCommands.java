package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Команды модуля заявок для других модулей. Выполняются в транзакции вызывающего и в его арендаторе
 * ({@code TenantContext}). Права пользователя не проверяются: это ответственность вызывающего модуля.
 * Ошибки данных — {@link ru.easyitsm.platform.web.ApiException} с кодом из {@code docs/api/tickets-contract.md}.
 */
public interface TicketCommands {

    /** Создаёт заявку: номер, приоритет по матрице, маршрутизация, событие TicketCreated. */
    TicketRef create(NewTicket ticket);

    /**
     * Итог согласования запроса (цикл 5.4): из статуса категории APPROVAL в «Согласована» или «Отклонена».
     *
     * @param reason обязательна при отклонении, попадает в публичную ленту
     */
    TicketRef applyApprovalOutcome(UUID ticketId, boolean approved, String reason);

    /**
     * Запись в ленту от имени пользователя или системы (почта, мессенджер, автоматизация).
     * Публичный ответ заявителя в решённой заявке возвращает её в работу (INC-08).
     * Секреты в тексте маскируются (TCK-17).
     *
     * @param authorId null — системная запись
     * @return id записи ленты
     */
    UUID addComment(UUID ticketId, UUID authorId, String body, Visibility visibility);

    /** Эскалация вручную или по правилу SLA (INC-04). Для FUNCTIONAL обязательна группа. */
    TicketRef escalate(UUID ticketId, EscalationKind kind, UUID targetGroupId, String reason, UUID actorId);

    /**
     * Ближайший срок SLA для сортировки очереди агента ({@code sort=due}). Вызывает модуль sla при каждом
     * пересчёте целей; null — целей нет или все выполнены. Версия заявки и лента не меняются.
     */
    void updateSlaDue(UUID ticketId, Instant slaDueAt);
}
