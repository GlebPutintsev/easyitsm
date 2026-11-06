# Контракт модуля крупных инцидентов (mim)

Java: `ru.easyitsm.mim.api`, `ru.easyitsm.mim.events`. REST: `/api/v1/major-incidents`, `/api/v1/mim/templates`,
`/api/v1/mim/chains`, `/api/v1/status`. Требования MIM-01…05, экран 10.9, страница статуса 10.2.

## Модель

- Крупный инцидент — надстройка над заявкой-инцидентом модуля tickets. Заявка остаётся главной: приоритет,
  лента, SLA, решение. Модуль хранит обоснование, роли, хронологию, задачи, сообщения, оповещения и разбор.
- Состояния: `requested` (агент запросил, ждёт LEAD/MIM) → `active` (объявлен) → `restored` (сервис
  восстановлен, идёт разбор) → `closed`; `requested → rejected`. На одну заявку — один действующий.
- При объявлении заявка получает P1 (`TicketCommands.change`), менеджер становится наблюдателем, в ленту
  пишется внутренняя заметка.
- Роли: `manager` (только пользователь с ролью MIM или LEAD, обязателен), `tech_lead`, `comms`, `scribe`
  (сотрудники поддержки).
- Дочерние инциденты — связь заявок `parent_of` (`TicketLinks`). Сообщение пользователям становится
  публичной записью в ленте крупного инцидента и всех открытых дочерних (INC-09).
- Шаблоны сообщений (MIM-04): переменные `{{number}} {{title}} {{services}} {{status}} {{hypothesis}}
  {{nextUpdate}} {{startedAt}} {{duration}}`, время — в часовом поясе пользователя (по умолчанию Москва).
  Готовые шаблоны заводятся при первом обращении арендатора.
- Оповещение дежурных (MIM-03): цепочка шагов (группа или пользователь, ожидание 1–240 мин). В группе
  оповещаются дежурные смены (`DutyRoster`), без смены — руководитель, без него — активные участники (до 20).
  Нет подтверждения в срок — следующий шаг (задача worker раз в 30 с), шаги кончились — `exhausted` и
  уведомление менеджеру. Подтверждает любой оповещённый, оповещение становится `acknowledged`.
  Каналы: `center` (центр уведомлений и push) и `email`; прочие каналы слушают `OnCallPaged`.
- Закрытие (MIM-05) — только из `restored`: черновик PIR в Markdown (сводка, участники, хронология,
  сообщения, задачи) и, по желанию, проблема через `ProblemCommands.create` (`source = major_incident`,
  инциденты — главный и дочерние, хронология копируется).

## Права

- Объявить: поддержка (`AGT, LEAD, MIM, CHG, PRB, CFG, AST`), инцидент должен быть виден. `LEAD`/`MIM` —
  сразу `active`, остальные — `requested`. Согласовать или отклонить — `LEAD`, `MIM`.
- Читать командный центр: кто видит заявку, участник (роль, задача, оповещение), `MIM`, `AUD`. Иначе 404.
- Вести (гипотеза, роли, задачи, сообщения, оповещения, восстановление, PIR): `MIM`, `LEAD` и держатель
  роли из поддержки. Иначе 403. Задачу отмечает ещё и её исполнитель.
- Закрыть: `MIM`, `LEAD`, менеджер инцидента.
- Шаблоны и цепочки: читает поддержка и `ADM`, правят `MIM` и `ADM` (журнал версий настроек).
- `/status` — любой вошедший: только публичное название, услуги и сообщения пользователям со страницы статуса.

## REST

| Метод | Путь | Что |
| --- | --- | --- |
| POST | `/major-incidents` | Объявить: `ticketId, reason, managerId?, serviceIds?, publicTitle?` → 201 |
| GET | `/major-incidents?state=open\|requested\|active\|closed\|all` | Список |
| GET | `/major-incidents/{id}`, `/major-incidents/by-ticket/{ticketId}` | Командный центр |
| POST | `/major-incidents/{id}/approve`, `/reject` (`reason`) | Согласование |
| PATCH | `/major-incidents/{id}` + If-Match | `hypothesis, serviceIds, publicTitle, nextUpdateAt` |
| PUT | `/major-incidents/{id}/roles/{role}` | `userId` (null — снять) |
| POST | `/major-incidents/{id}/events` | Запись хронологии `text, at?` |
| POST | `/major-incidents/{id}/tasks`; PUT `/tasks/{taskId}/done` | Задачи |
| POST | `/major-incidents/{id}/children` | `incidentIds` |
| POST | `/major-incidents/{id}/render` | Шаблон → `title, body, audience` |
| POST | `/major-incidents/{id}/updates` | `audience (users\|management), title, body, statusPage, nextUpdateAt` |
| POST | `/major-incidents/{id}/pages` | `chainId` или `steps`, `channel` |
| POST | `/major-incidents/{id}/pages/{pageId}/cancel`; `/major-incidents/pages/{pageId}/ack` | Оповещения |
| POST | `/major-incidents/{id}/restore` (`at?, comment?`), `/close` (`createProblem, problemTitle?`) | |
| GET | `/major-incidents/{id}/pir-draft`; PUT `/pir` + If-Match | Разбор |
| GET/POST/PUT | `/mim/templates[/{id}]`, `/mim/chains[/{id}]` (`?all=true` — с выключенными) | Настройки |
| GET | `/status` | Страница статуса: крупные инциденты и действующие объявления |

ETag — версия крупного инцидента. Коды ошибок: `not_incident`, `ticket_closed`, `already_major`,
`invalid_manager`, `manager_required`, `invalid_user`, `invalid_role`, `invalid_state`, `not_restored`,
`invalid_audience`, `invalid_channel`, `invalid_step`, `steps_required`, `invalid_time`, `invalid_filter`.

## Java API

```java
List<ActiveMajorIncident>     MajorIncidentQueries.active()           // active и restored, новые первыми
Optional<ActiveMajorIncident> MajorIncidentQueries.forTicket(ticketId)
```

Без проверки прав, в арендаторе вызывающего; наружу — номер, публичное название, состояние, услуги.

## События

| Событие | Когда |
| --- | --- |
| `MajorIncidentDeclared` | объявлен (сразу или после согласования) |
| `MajorIncidentUpdatePublished` | опубликовано сообщение (`audience`, `statusPage`) |
| `OnCallPaged` | оповещён дежурный (`userId, step, channel, link`) — для мессенджеров и SMS |
| `MajorIncidentClosed` | закрыт (`declaredAt, restoredAt, problemId`) |
