package ru.easyitsm.tickets.api;

import java.time.Instant;
import java.util.Optional;
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

    /**
     * Изменение полей от имени системы (автоматизация): приоритет, влияние, срочность, группа, исполнитель.
     * Закрытая заявка — {@code ticket_closed}. Группа и исполнитель не сверяются со справочником identity:
     * вызывающий модуль проверяет их сам. Ничего не изменилось — версия и события остаются прежними.
     */
    TicketRef change(UUID ticketId, TicketChange change);

    /**
     * Переход от имени системы по любому ручному переходу цикла из текущего статуса. {@code trigger} попадает
     * в {@code TicketStatusChanged.trigger}, например {@code automation}. Нет такого перехода —
     * {@code transition_not_allowed}, не хватает полей — {@code field_required}.
     */
    TicketRef transition(UUID ticketId, TicketTransition transition, String trigger);

    /**
     * Решение заявки от имени системы, когда внешний источник сообщил, что проблема устранена (мониторинг).
     * Выбирается первый ручной переход цикла из текущего статуса в статус категории RESOLVED, обязательные поля
     * которого заполнены: комментарий, код и текст решения, а также группа и исполнитель, если они есть у заявки.
     * Такого перехода нет (заявка новая, без исполнителя, уже решена или закрыта) — пусто, заявка не меняется
     * и исключения нет: вызывающий сам решает, что делать (например, оставить заметку).
     */
    Optional<TicketRef> resolveIfPossible(UUID ticketId, String comment, String resolutionCode, String resolutionText,
                                          String trigger);

    /**
     * Подписывает пользователя на заявку. Причина — {@code added} (по умолчанию), {@code mention} или {@code creator}.
     *
     * @return true — подписан впервые
     */
    boolean addWatcher(UUID ticketId, UUID userId, String reason);
}
