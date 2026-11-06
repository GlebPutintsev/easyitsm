# Контракт модуля изменений (changes)

CHG-01…12 (1.0). Пакет `ru.easyitsm.changes`, таблицы `change_detail`, `change_step`, `change_approval`,
`change_template`, `change_freeze`, `change_settings`, `cab_meeting`, `cab_item`, `cab_vote` (все с RLS).
Модуль зависит от `tickets.api`, `cmdb.api`, `identity.api`, `notifications.api`, `admin.api`, `audit.api`;
от него не зависит никто, кроме вызовов через Java API ниже.

## Изменение — это заявка типа `change`

Номер `CHG-…`, тема, описание, группа, исполнитель, наблюдатели, комментарии и вложения — у заявки (`tickets`).
Остальное — у модуля изменений, он же владеет статусом: переходы цикла `change` в `tickets` доступны только
системе (`roles: [system]`), поэтому статус заявки меняется только через REST изменений.

| Статус изменения | Статус заявки | Категория |
| --- | --- | --- |
| `draft` | `draft` | draft |
| `assessment` | `assessment` | new |
| `approval` | `approval` | approval |
| `scheduled` | `scheduled` | assigned |
| `implementation`, `verification` | то же | in_progress |
| `review` | `review` | resolved |
| `closed` | `closed` | closed |
| `rejected`, `cancelled` | то же | cancelled |

Типы: `standard` — только из шаблона с предварительным утверждением, сразу `scheduled`; `normal` — оценка,
риск, маршрут согласования; `emergency` — сразу `scheduled` (или `approval`, если в настройках включено
предварительное согласование), согласования «после факта» со сроком `postApprovalDueAt` (рабочие дни после окна).

## Java API (`ru.easyitsm.changes.api`)

- `ChangeCommands.create(NewChange)` → `ChangeRef(id, number, state)` — например, из проблемы (`sourceTicketId`).
- `ChangeQueries.find(id)` → `Optional<ChangeSummary>`; `byCi(ciId, limit)` — изменения КЕ, новые первыми.
  Без проверки прав: показывать пользователю через REST.

## События (`ru.easyitsm.changes.events`, после фиксации)

| Событие | Когда |
| --- | --- |
| `ChangeStateChanged` | любой переход (`fromState`, `toState`) |
| `ChangeApproved` | изменение утверждено (все согласования раунда, CAB или стандартное по шаблону) |
| `ChangeCompleted` | внедрение завершено: `outcome` = `successful`, `partial`, `rolled_back`; факт начала и конца |

## Слушает

- `TicketEvent` заявок типа `change` — копия номера, темы, группы и исполнителя для списков и календаря.
- `cmdb.events.CiMerged` — дубликат КЕ слит при сверке: `ci_ids` и `service_ids` изменений, шаблонов и заморозок
  переводятся на выжившую КЕ.

## REST

Читают `AGT, LEAD, MIM, CHG, PRB, CFG, AST, AUD, APR`; поддержка и аудитор видят все неконфиденциальные
изменения (расписание), согласующий — свои. Работают с изменением автор, исполнитель, участники группы и CHG.
Все изменяющие запросы — с `If-Match` (428 без него, 412 при устаревшей версии); создание — с `Idempotency-Key`.

| Метод | Путь | |
| --- | --- | --- |
| GET | `/changes?state=&type=&risk=&ciId=&serviceId=&groupId=&q=&mine=&from=&to=&cursor=&limit=` | список |
| POST | `/changes` | `{type, title, description, templateId, sourceTicketId, groupId, ciIds, serviceIds}` → 201 |
| GET, PATCH | `/changes/{id}` | карточка; PATCH — поля формы ЗНИ (`goal`, `justification`, `impact`, `risks`, `impactRows`, `systemCriticality`, окно, `timeZone`, `prepStart`, `downtimeMinutes`, `ciIds`, `serviceIds`) |
| PUT | `/changes/{id}/plan` | `{steps[]}`: фазы `prepare`, `implement`, `verify`, `rollback`; результаты сохраняются по `id` шага |
| GET / PUT | `/changes/risk-questions`, `/changes/{id}/risk` | анкета; `{answers, level?, reason?}` — ручной уровень с причиной |
| POST | `/changes/{id}/submit`, `/return`, `/send-to-approval` (CHG), `/reschedule`, `/start`, `/rollback`, `/verify`, `/reopen`, `/complete`, `/close` (CHG), `/cancel` | переходы |
| POST | `/changes/{id}/approvals/{approvalId}/decision` | `{decision: approve\|reject, comment}`, с повторным входом; автор — 403 `separation_of_duties` |
| POST | `/changes/{id}/steps/{stepId}/result` | `{status: done\|failed\|skipped\|pending, note}` |
| GET | `/changes/{id}/conflicts`, POST `/changes/conflicts/check` | пересечения по КЕ, услугам и окну, заморозки |
| GET | `/changes/{id}/impact?depth=` | услуги по связям CMDB (CHG-06) |
| GET | `/changes/{id}/related-incidents` | инциденты по тем же КЕ и услугам от начала работ до +72 ч (CHG-11) |
| GET | `/changes/{id}/plan-document?format=xlsx\|docx\|pdf` | план работ по форме ЗНИ, запись в журнал аудита |
| GET | `/changes/calendar?from=&to=` (до 100 дней), `/changes/calendar.ics`, `/changes/{id}/ical` | календарь и iCal |
| GET, POST, PATCH | `/change-templates` (CHG, ADM) | шаблоны; `pipelineKey` — ключ для CI/CD |
| GET, POST, PATCH, DELETE | `/change-freezes` (CHG, ADM) | заморозки `all` или `targeted` по услугам и КЕ |
| GET, PUT | `/change-settings` (CHG, ADM) | маршруты по уровню риска, экстренные, обязательность КЕ/услуг, часовой пояс |
| GET, POST, PATCH | `/cab/meetings`, `/cab/meetings/{id}` | заседания CAB |
| POST | `/cab/meetings/{id}/collect`, `/items`, `/start`, `/complete`, `/cancel`, `/protocol` | повестка и ход заседания |
| POST | `/cab/meetings/{id}/items/{itemId}/vote`, `/decision` | голос участника; решение председателя или CHG (с повторным входом) |

### Конфликты и заморозки (CHG-05, AT-06)

`send-to-approval`, `reschedule` и `submit` стандартного проверяют пересечения. Заморозка — 409
`change_freeze`. Пересечение с другим изменением — 409 `change_conflicts` со списком `conflicts[]`; повтор с
`acknowledgeConflicts: "причина"` проходит, причина пишется в заявку внутренним комментарием.

### Маршрут согласования (CHG-04)

`change-settings.doc.{low|medium|high|emergency}` = `{manager, groupId, cab}`. По умолчанию: низкий —
менеджер изменений, средний и высокий — менеджер и CAB. Слот `cab` решается только на заседании (409
`cab_decision_required` через обычное согласование). Перенос окна согласованного обычного изменения начинает
новый раунд.

### CI/CD (CHG-10, учётная запись с ролью SVC)

1. `POST /changes/pipeline` `{templateKey, title, system, repository, ref, environment, pipelineUrl,
   windowStart, windowEnd, ciIds, serviceIds}` — изменение по шаблону; стандартное с окном сразу `scheduled`.
2. `GET /changes/{id}/deploy-check` → `{allowed, reason: state|window|freeze, message}` перед выкаткой.
3. `POST /changes/{id}/pipeline/started`, `/pipeline/finished {outcome, conclusion}`.
