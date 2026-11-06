# Контракт модуля заявок (tickets)

REST: [`tickets.yaml`](tickets.yaml) (OpenAPI 3.1). Java: пакеты `ru.easyitsm.tickets.api` и `ru.easyitsm.tickets.events`.
Контракт стабилен в рамках `/api/v1`: поля добавляются, но не удаляются и не меняют смысл.

## Модель

- Одна сущность `ticket` для всех типов: `incident`, `request`, `problem`, `change`, `task` (TCK-01).
  Номер `INC-000123`, счётчик отдельный на тип и арендатора (TCK-02).
- Статус — код из настраиваемого цикла типа (`ticket-settings/workflows/{type}`). Поверх него фиксированная
  **категория** `StatusCategory`, на которую опираются другие модули:

  | Категория | Значение | SLA |
  | --- | --- | --- |
  | `draft` | Черновик запроса | не считается |
  | `new` | Создана, не назначена или согласована и ждёт группу | идёт |
  | `approval` | Запрос на согласовании (цикл 5.4) | решает SLA-тред |
  | `assigned` | Назначена на группу | идёт |
  | `in_progress` | В работе | первый переход = реакция |
  | `pending` | Ожидание, всегда с причиной | пауза |
  | `resolved` | Решена, ждёт подтверждения или автозакрытия | решение |
  | `closed`, `cancelled` | Финал | остановка |

- Циклы по умолчанию:
  - incident: `new → assigned → in_progress ⇄ pending → resolved → closed`, `new/assigned → cancelled` (5.1);
  - request: `draft → new`, `pending_approval → approved | rejected`, `approved → in_progress → fulfilled → closed`, `cancelled` (5.4);
  - problem, change: `new → in_progress ⇄ pending → resolved → closed`, `cancelled` — модули problems и changes расширят;
  - task (подзадачи): `new → in_progress ⇄ pending → done (closed)`, `cancelled`.
- Переход описан как `from, to, roles, requiredFields, trigger`. Роли — коды ТЗ (`AGT`, `LEAD`, `MIM`…)
  и псевдороли `requester`, `assignee`, `system`. Триггеры: `manual`, `group_assigned` (группа задана в
  статусе `new`), `requester_reply` (INC-08), `timer` (автозакрытие INC-07, возврат из ожидания по сроку),
  `approval` (итог согласования).
- Приоритет по матрице влияние × срочность (5.2), ручной — с причиной, флаг `priorityManual`.
- Маршрутизация (5.8): прямое назначение → правила по порядку (тип, услуга, категория, КЕ, канал,
  приоритет, ключевые слова, язык) → распределение внутри группы (`none`, `round_robin`, `least_loaded`)
  → группа по умолчанию. Подсказка ИИ — позже, модуль ai-client.
- Настраиваемые поля (TCK-07) хранятся в `ticket.custom` (JSONB) по коду поля и проверяются по определению.
- Очередь агента: `sort=due` по `slaDueAt` (копия ближайшего срока, её обновляет модуль sla через
  `TicketCommands.updateSlaDue`), `awaitingAgent` (последняя публичная запись от заявителя),
  `GET /tickets/count`, `updatedFrom`, `dueBefore` (просроченные: ближайший из `slaDueAt`/`dueAt` раньше
  переданного времени), несколько `groupId`/`assigneeId` через запятую, `none` — без группы или исполнителя.
- Значения по умолчанию при создании: влияние `low`, срочность `medium`, значит приоритет `P4`.
- Фронтенд: типы и хуки в `frontend/src/features/tickets/api.ts` (`ticketsApi` для адаптеров,
  `useTickets`, `useTicket`, `useTransitionTicket` и т. д.); If-Match берётся из `version` записи.
- Сохранённые представления `/ticket-views` (TCK-16) и готовые ответы `/response-templates` (TCK-09):
  личные, групповые, общие.
- Описание и записи ленты — Markdown без HTML; клиент рендерит его без `dangerouslySetInnerHTML`.

## Права (раздел 3)

- Создать: все роли, кроме `AUD`. Заявителем другого человека может указать только поддержка.
- Поддержка = `AGT, LEAD, MIM, CHG, PRB, CFG, AST`: внутренние заметки, поля, назначение.
- Область видимости берётся из `AccessDecisions.currentScope()` модуля identity (группы пользователя, роли,
  организация по политике арендатора) внутри `AccessScopes` модуля tickets. Правила чтения:
  - участник видит всегда: заявитель, получатель (`createdFor`), исполнитель, наблюдатель и тот, кто
    зарегистрировал заявку (`createdBy`, например агент по звонку);
  - конфиденциальную (ACC-04), кроме участников, — только члены её группы безопасности `securityGroupId`;
  - остальные: `MIM`, `AUD` — все; `AGT, LEAD, CHG, PRB, CFG, AST, KNW` — заявки своих групп
    (`groupId` ∈ группы пользователя); вся поддержка — ещё нераспределённые (без группы); заявитель — заявки своей
    организации, если это включено политикой identity (по умолчанию выключено).
  Невидимая заявка — 404, в списках её нет.
- Работать с заявкой (поля, назначение, взять, эскалация, связи, подзадачи, слияние, подписка других,
  переходы по ролям поддержки) может поддержка, которая видит заявку и при этом исполнитель, член
  назначенной группы, `MIM`, а у конфиденциальной — член группы безопасности. Нераспределённую берёт любой
  из поддержки, кто её видит. Видимая, но чужая — 403. Агент чужой группы, подписанный наблюдателем, читает
  и комментирует, но не меняет. В ответе `permissions.edit/assign/merge` это учитывают.
- Назначение пользователем: группа должна быть в справочнике identity, исполнитель — состоять в группе
  заявки, иначе 400 `invalid_assignment` с `field` (`groupId` или `assigneeId`). Маршрутизация и вызовы
  других модулей не проверяются.
- «Взять следующую» берёт только из групп, где пользователь состоит; чужой `groupId` — 403.
  Фильтр списков `groupId=mine` — группы текущего пользователя.
- `REQ`, `APR` и любой пользователь без роли поддержки видят заявки, где они участник; только публичная
  лента; переходы с псевдоролью `requester`.
- `AUD` — только чтение, включая заметки. `ADM` — настройки (`/ticket-settings/**`).

## Java API для модулей

```java
TicketRef     TicketCommands.create(NewTicket)                         // каталог, почта, мониторинг
TicketRef     TicketCommands.applyApprovalOutcome(id, approved, reason) // каталог после согласования
UUID          TicketCommands.addComment(id, authorId, body, visibility) // почта, мессенджеры
TicketRef     TicketCommands.escalate(id, kind, groupId, reason, actor) // SLA при нарушении
void          TicketCommands.updateSlaDue(id, slaDueAt)               // SLA: срок для sort=due
TicketRef     TicketCommands.change(id, TicketChange)                 // автоматизация: приоритет, влияние, срочность, группа, исполнитель
TicketRef     TicketCommands.transition(id, TicketTransition, trigger) // автоматизация: переход по ручному переходу цикла
Optional<TicketRef> TicketCommands.resolveIfPossible(id, comment, resolutionCode, resolutionText, trigger) // мониторинг: «устранено»
boolean       TicketCommands.addWatcher(id, userId, reason)           // автоматизация: подписка
Optional<TicketSnapshot> TicketQueries.find(id) / findByNumber(number)
Optional<TicketSnapshot> TicketQueries.findVisible(id)                // с правами текущего пользователя
Optional<TicketComment>  TicketQueries.findComment(ticketId, activityId) // текст ответа или заметки для писем
List<UUID>               TicketQueries.watchers(ticketId)
List<TicketSnapshot>     TicketQueries.openForObjects(ciOrServiceIds, limit) // открытые заявки по КЕ/услугам, без прав (CMDB)
List<SecretFinding>      SecretScanner.scan(text);  String SecretScanner.mask(text)
Optional<TicketPermissions> TicketQueries.permissions(id)             // вложения: права текущего входа
// Поиск (TicketSearchIndex): документ для индекса, область чтения данными и перепроверка выдачи
Optional<TicketSearchDocument> TicketSearchIndex.document(id);  List<UUID> idsAfter(afterId, limit)
TicketReadScope TicketSearchIndex.readScope();  Set<UUID> visibleAmong(ids);  List<TicketSearchHit> search(TicketTextQuery)
```

Вызов идёт в арендаторе вызывающего (`TenantContext`) и без проверки прав пользователя. Исключение —
`findVisible`: те же правила, что у `GET /tickets/{id}` для текущего входа (участник, область видимости,
конфиденциальность); невидимая заявка — пусто. Так база знаний проверяет заявку перед статьёй из неё (KB-05).
`permissions` — видимость как у `findVisible` и права текущего входа: `canComment`, `canSeeInternal`,
`canAddInternal`, `closed`. `TicketReadScope.canRead(doc)` — те же правила видимости в виде данных
(группы, конфиденциальность, участники, организация); модуль поиска переводит их в фильтр OpenSearch и затем
перепроверяет страницу выдачи через `visibleAmong`, так что устаревший индекс не раскрывает заявку.
`search` — запасной поиск PostgreSQL (`to_tsquery('russian')`, подсветка `ts_headline` маркерами
`TicketSearchIndex.MARK_START/MARK_END`).
`change`, `transition` и `addWatcher` выполняются от имени системы (`actorId = null` в событиях). Группа и исполнитель
в `change` не сверяются с identity — это делает вызывающий модуль. `transition` берёт любой ручной переход из текущего
статуса, обязательные поля перехода проверяются; `trigger` попадает в `TicketStatusChanged.trigger` (`automation`).
`resolveIfPossible` ищет первый ручной переход из текущего статуса в статус категории `resolved`, обязательные поля
которого уже заполнены (комментарий, код и текст решения, группа и исполнитель заявки). Нет такого перехода — пусто
без исключения и без изменений: так мониторинг не решает инцидент, который ещё никто не взял.
`NewTicket.awaitingApproval = true` создаёт запрос в статусе категории `approval` (`pending_approval`).
Влияние в `NewTicket` — `HIGH | MEDIUM | LOW`; портал сам сопоставляет «только я / подразделение / все».

## События (`ru.easyitsm.tickets.events`)

Все реализуют `TicketEvent { tenantId, occurredAt, actorId, ticket: TicketSnapshot }`. `occurredAt` —
время бизнес-события, `ticket` — снимок после изменения (тип, приоритет, статус и категория, группа,
исполнитель, услуга, организация, контрольные моменты), обратные запросы не нужны.

| Событие | Когда | Доп. поля |
| --- | --- | --- |
| `TicketCreated` | создание | — |
| `TicketStatusChanged` | любой переход | `fromStatusCode, fromCategory, reasonCode, reasonText, trigger` |
| `TicketPriorityChanged` | смена приоритета | `fromPriority, manual, reason` |
| `TicketAssignmentChanged` | группа или исполнитель | `fromGroupId, fromAssigneeId` |
| `TicketUpdated` | прочие поля | `fields` |
| `TicketFirstResponded` | первая реакция, один раз | `via = status | reply` |
| `TicketCommentAdded` | ответ или заметка | `activityId, visibility, fromRequester, mentionedUserIds` |
| `TicketWatcherAdded` | подписка | `userId, reason` |
| `TicketEscalated` | эскалация | `kind, level, fromGroupId, reason` |
| `TicketMerged` | слияние | `sourceTicketIds` |

`reasonCode` в `TicketStatusChanged`: причина ожидания (журнал пауз SLA-04), причина отмены,
`reopened` при возврате из `resolved`. Текст комментариев в события не попадает.

## Коды ошибок

`not_found`, `transition_not_allowed`, `field_required` (+`field`), `open_subtasks`,
`identity_check_required`, `ticket_closed`, `secrets_detected` (+`findings`), `invalid_custom_field` (+`field`),
`invalid_assignment` (+`field`), `link_exists`, `link_cycle`, `merge_invalid`, `undo_expired`, `version_conflict`,
`if_match_required`, `forbidden`.

## Чего нет в модуле

Вложения (модуль attachments, docs/api/search-attachments.md), опрос удовлетворённости (модуль surveys
портала), SLA-таймеры (модуль sla), уведомления (notifications слушает события), индекс OpenSearch
(модуль search; в tickets — документы для индекса и запасной поиск PostgreSQL).
