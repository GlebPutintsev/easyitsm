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
- Описание и записи ленты — Markdown без HTML; клиент рендерит его без `dangerouslySetInnerHTML`.

## Права (раздел 3)

- Создать: все роли, кроме `AUD`. Заявителем другого человека может указать только поддержка.
- Поддержка = `AGT, LEAD, MIM, CHG, PRB, CFG, AST`. Видит заявки, внутренние заметки, меняет поля.
  Ограничение «только своих групп» включится, когда модуль identity отдаст членство в группах
  (интерфейс `GroupMembership` внутри tickets, сейчас — без ограничения по группам).
- `REQ`, `APR` и любой пользователь видят заявки, где они заявитель, получатель (`createdFor`) или
  наблюдатель; только публичная лента; переходы с псевдоролью `requester`.
- `AUD` — только чтение, включая заметки. `ADM` — настройки (`/ticket-settings/**`).
- Конфиденциальная заявка (ACC-04) видна только участникам (заявитель, получатель, исполнитель,
  наблюдатели) и `SEC`. Чужая или недоступная заявка — 404.

## Java API для модулей

```java
TicketRef     TicketCommands.create(NewTicket)                         // каталог, почта, мониторинг
TicketRef     TicketCommands.applyApprovalOutcome(id, approved, reason) // каталог после согласования
UUID          TicketCommands.addComment(id, authorId, body, visibility) // почта, мессенджеры
TicketRef     TicketCommands.escalate(id, kind, groupId, reason, actor) // SLA при нарушении
Optional<TicketSnapshot> TicketQueries.find(id) / findByNumber(number)
List<SecretFinding>      SecretScanner.scan(text);  String SecretScanner.mask(text)
```

Вызов идёт в арендаторе вызывающего (`TenantContext`) и без проверки прав пользователя.
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
`link_exists`, `link_cycle`, `merge_invalid`, `undo_expired`, `version_conflict`, `if_match_required`, `forbidden`.

## Чего нет в модуле

Вложения (тред «Поиск и вложения»), опрос удовлетворённости (модуль surveys портала), SLA-таймеры
(модуль sla), уведомления (notifications слушает события), полнотекстовый поиск OpenSearch
(в tickets — резервный поиск PostgreSQL по `search_vector`).
