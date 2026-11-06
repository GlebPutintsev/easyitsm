# Контракт модуля автоматизации (automation)

AUT-01 и AUT-04 (MVP). Java API и событий у модуля нет: он слушает `tickets.events` и `sla.events` и действует
через `TicketCommands` от имени системы. Пакет `ru.easyitsm.automation`, таблицы `automation_rule`, `automation_run`.

## Правило

```json
{
  "name": "Сбой для всех — P1",
  "trigger": "ticket_created",
  "conditions": { "match": "all", "items": [{ "field": "impact", "op": "eq", "value": "high" }] },
  "actions": [{ "type": "set_priority", "priority": "P1" }, { "type": "assign", "groupId": "…" }],
  "enabled": true,
  "position": 10
}
```

- **События**: `ticket_created`, `status_changed`, `priority_changed`, `assignment_changed`, `ticket_updated`,
  `comment_added`, `escalated`, `sla_warning`, `sla_breached`.
- **Поля условий**: поля снимка заявки (`type`, `priority`, `impact`, `urgency`, `statusCode`, `statusCategory`,
  `groupId`, `assigneeId`, `serviceId`, `categoryId`, `catalogItemId`, `channel`, `confidential`, `title`,
  `requesterId`, `organizationId`) и поля события: `fromStatusCode`, `fromStatusCategory`, `transitionTrigger`,
  `fromPriority`, `fromGroupId`, `fromAssigneeId`, `changedFields`, `visibility`, `fromRequester`, `escalationKind`,
  `escalationLevel`, `slaKind`, `slaMetric`, `slaPercent`. Коды в нижнем регистре (`p1`, `in_progress`).
- **Операции**: `eq`, `neq`, `in`, `not_in`, `empty`, `not_empty`, `contains`/`not_contains` (тема, `changedFields`),
  `gte`/`lte` (числа). До 20 условий, `match: all | any`.
- **Действия** (до 10, выполняются по порядку в одной транзакции — всё или ничего): `assign` (`groupId`, `assigneeId`),
  `set_priority` (`priority`), `set_status` (`status`, `body` — комментарий, `reasonCode`, `resolutionCode`),
  `add_comment` (`body` с `{{number}} {{title}} {{priority}} {{status}} {{rule}}`, `visibility`), `add_watcher` (`userId`),
  `escalate` (`kind`, `groupId`, `reason`). Уведомления, вебхуки и скрипты — AUT-05 (1.0).
- Действие `set_priority:Px` из настройки цели SLA тоже выполняет автоматизация (запись журнала «SLA: …»).

## Запуск и защита от петель (AUT-04)

Правило срабатывает на снимке заявки из события. Совпали условия — запись в журнал:

| Итог | Когда |
| --- | --- |
| `success` | действия выполнены (`results[]`: `applied` или `unchanged`) |
| `failed` | действие не выполнено, всё откатилось; `errorCode`, `error` («Действие 2 (set_status): …») |
| `skipped` / `depth_limit` | цепочка глубже 5 (`easyitsm.automation.max-depth`) |
| `skipped` / `loop` | правило уже есть в цепочке, которая привела к событию |
| `skipped` / `rate_limit` | правило выполнило действия на заявке 10 раз за 5 минут (`rate-limit`, `rate-window`) |

Повторная доставка того же события из outbox правило второй раз не запускает (`run_key`). Журнал хранится
90 дней (`keep-runs-days`). Цепочка отслеживается в потоке: слушатели вызываются синхронно после фиксации.
Поэтому слушатели других модулей, которые пишут в БД после фиксации, должны открывать **новую** транзакцию
(`REQUIRES_NEW`), иначе их блокировки живут до очистки чужой транзакции.

## REST (`/api/v1/automation`, читать ADM и AUD, менять ADM)

| Метод | Путь | |
| --- | --- | --- |
| GET | `/rules` | `{items}` по `position` |
| POST | `/rules` | 201, ETag; ошибка — 400 `invalid_rule` с местом в `detail` |
| GET, PATCH, DELETE | `/rules/{id}` | PATCH с `If-Match`; удаление оставляет журнал |
| POST | `/test` | `{rule, ticket}` — что сделает правило с заявкой сейчас, без изменений; `matched` = null, если зависит от поля события |
| GET | `/runs?ruleId=&status=&ticket=&cursor=&limit=` | журнал, новые сверху |
| GET | `/runs/{id}` | запись журнала |

Создание, изменение и удаление правила записывают версию в журнал настроек «Управления» (`ConfigHistory`, вид `automation.rule`, id правила, снимок правила целиком).
