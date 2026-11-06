# Контракт модуля проблем (problems)

PRB-01…05 (4.5 ТЗ), таблицы 7.4. Пакет `ru.easyitsm.problems`, миграция
`db/migration/problems/V2026_11_06_1155__problems_init.sql`.

## Модель

- Проблема — заявка типа `problem` модуля tickets (номер `PRB-…`, цикл, группа, исполнитель, лента, SLA).
  Список проблем — `GET /api/v1/tickets?type=problem`. Модуль хранит только то, что есть у проблемы сверх заявки:
  - `problem_detail`: источник (`manual`, `incident`, `recurring`, `major_incident`), корневая причина, обходное
    решение, анализ, флаг «решить связанные инциденты», принятый риск (кто, почему, когда пересмотреть);
  - `known_error`: одна известная ошибка на проблему — тема, симптомы, обходное решение, услуга, КЕ, категория,
    публикация, признак «исправлена» (проблема решена);
  - `problem_change`: изменения-исправления; `problem_incident`: инциденты, которые модуль связал с проблемой
    (для отметки «уже есть проблема» в отчёте повторов).
- Инцидент связывается с проблемой связью заявок `caused_by` (с её стороны — `causes`) через
  `tickets.api.TicketLinks`, поэтому связь видна и в карточке инцидента. Изменение — связью `relates_to`.
- Анализ причин (PRB-02) — JSON: `timeline` (до 200 событий `{at, text}`, по времени), `whys` («5 почему», до 10
  шагов `{question, answer}`), `ishikawa` (категории `people`, `process`, `technology`, `environment`, `suppliers`,
  `measurement`, до 20 причин по 500 символов), `conclusion`. При создании из инцидентов хронология получает
  их появление.
- Строки `problem_detail` может не быть (проблему создали через «+ Создать» заявок): карточка отдаёт значения
  по умолчанию с версией 0, первая запись создаёт строку.

## Права

| Действие | Кто |
| --- | --- |
| Создать проблему, править анализ и известную ошибку, связать инциденты, создать изменение, решить инциденты | поддержка `AGT, LEAD, MIM, CHG, PRB, CFG, AST`, которая работает с заявкой проблемы (`TicketPermissions.canWork`) |
| Опубликовать и снять известную ошибку, принять риск | `PRB, LEAD, MIM` и работа с заявкой |
| Читать проблему, подбор известных ошибок | поддержка и `AUD`, если заявка видна (`findVisible`) |
| Отчёт повторов | `PRB, LEAD, MIM, ANL` |

Невидимая проблема или инцидент — 404, видимая без права работы — 403, закрытая проблема — 409 `ticket_closed`.
Черновики известных ошибок в подборе видят только `PRB, LEAD, MIM` (`includeDrafts=true`).

## REST `/api/v1`

| Метод и путь | Назначение |
| --- | --- |
| `POST /problems` | `{title, description, incidentIds[≤100], source, serviceId, ciId, groupId, impact, urgency}` → 201 карточка. Услуга и КЕ по умолчанию — из первого инцидента. Не инцидент — 400 `not_incident`, неизвестная группа — 400 `invalid_assignment`. |
| `GET /problems/{ref}` | Карточка: поля заявки, анализ, известная ошибка, инциденты и изменения (невидимые — только номером, `visible=false`), `permissions`. ETag — версия данных проблемы. |
| `PUT /problems/{ref}/analysis` | If-Match. `{rootCause, workaround, analysis, closeIncidents}`, null — не менять. |
| `POST /problems/{ref}/accept-risk` | If-Match. `{reason, reviewAt}`, по умолчанию пересмотр через 90 дней. |
| `POST /problems/{ref}/incidents` | `{incidentIds}` — связать ещё инциденты. |
| `POST /problems/{ref}/changes` | `{title, description}` → 201 изменение (заявка `CHG-…`, связь, `problem_change`). Модуль changes заводит карточку изменения по `TicketCreated`. |
| `POST /problems/{ref}/resolve-incidents` | `{comment}` → `{resolved: [номера], skipped: [{number, reason}]}`. Только у решённой или закрытой проблемы, иначе 409 `problem_not_resolved`. |
| `PUT /problems/{ref}/known-error` | Создать (без If-Match) или изменить (If-Match — версия известной ошибки). |
| `POST /problems/{ref}/known-error/publish`, `/unpublish` | If-Match — версия известной ошибки. |
| `GET /known-errors` | `ticketId` — подбор к заявке (КЕ ×3, услуга ×2, категория ×1, слова темы), иначе `q`, `serviceId`, `ciId`, `categoryId`; `includeDrafts`, `limit ≤ 50`. Исправленные — ниже. |
| `GET /problems/recurring` | `dimension=service|category|ci`, `days` (1–366, по умолчанию 30), `minCount` (≥ 2, по умолчанию 3), `limit` → группы (`name` для `ci` — имя КЕ из CMDB) `{key, name, count, open, firstAt, lastAt, incidentIds (видимые), sample, problems}`. |

## Решение инцидентов (PRB-04)

Когда заявка проблемы переходит в категорию `resolved`, модуль:
1. помечает известную ошибку исправленной (возврат в работу снимает отметку);
2. если `closeIncidents`, решает открытые связанные инциденты от имени системы: из «В работе» и «Ожидания» —
   переход `resolved` с кодом `fixed` и текстом «Причина устранена в рамках проблемы PRB-…», из «Новой» и
   «Назначенной» — через «В работе», если есть исполнитель; без исполнителя инцидент остаётся агенту
   (`no_assignee`). Каждый инцидент — в своей транзакции, отказ одного не мешает остальным;
3. публикует `ProblemResolved`.

Изменение-исправление в категории `closed` пишет внутреннюю заметку в проблему и уведомляет её исполнителя.

## Java API и события

```java
ProblemRef ProblemCommands.create(NewProblem)   // разбор крупного инцидента (MIM-05) и другие модули, без проверки прав
```

`ru.easyitsm.problems.events`: `ProblemResolved(tenantId, occurredAt, problemId, number, resolvedIncidents)`,
`KnownErrorPublished(tenantId, occurredAt, knownErrorId, problemId, actorId)`.

## Что нужно от модуля заявок

`tickets.api.TicketLinks` (связи от имени системы), `TicketTrends.recurringIncidents` (группы инцидентов прямо из
таблицы заявок, конфиденциальные не входят) и `TicketPermissions.canWork`.
