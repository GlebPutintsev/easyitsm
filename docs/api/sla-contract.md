# Контракт модуля SLA (sla)

Java: `ru.easyitsm.sla.api` и `ru.easyitsm.sla.events`. REST: `/api/v1/sla/**`.

## Как считается

- **Политика** (`sla_policy`) вида `sla`, `ola` или `uc` с условиями: типы заявок, приоритеты, услуги,
  организации (клиенты), группы (SLA-02). Пустой список — любое значение. Для заявки в каждом виде действует
  одна политика: первая подходящая по `rank` (меньше — раньше).
- **Цель** (`sla_target`): показатель `response` или `resolution`, приоритет, срок `amount` + `unit`
  (`minutes`, `hours`, `business_days`), календарь `policy` (график политики) или `24x7`, пороги
  (по умолчанию 50, 75, 90) и действия на пороге и при нарушении (SLA-05).
  Рабочий день = самая частая длина дня в графике (9 ч для 09:00–18:00).
- **График** (`work_schedule`): часовой пояс и часы по дням недели, например `09:00-13:00,14:00-18:00`.
  **Исключения** (`holiday`) на даты: пусто — выходной, иначе особые часы (сокращённый или перенесённый
  рабочий день). Переходы на летнее время учитываются через часовой пояс (SLA-03).
- **Счётчик** (`sla_instance`) хранит старт, цель и журнал исключённых интервалов (`sla_pause`). Срок, пороги
  и нарушение каждый раз вычисляются заново от старта, поэтому пересчёт точен (NFR-05: эталонные тесты
  совпадают до миллисекунды).

## Реакция на события заявок

| Категория статуса | Действие |
| --- | --- |
| `draft`, `approval` | SLA не стартует; запущенные счётчики на паузе |
| `new`, `assigned`, `in_progress` | Старт по подходящим политикам; снятие паузы |
| статусы из `pauseStatuses` политики (по умолчанию `pending`) | Пауза с причиной `reasonCode: reasonText` (SLA-04) |
| `resolved`, `closed` | Завершение счётчиков моментом `resolvedAt` |
| возврат из `resolved` | Счётчик решения продолжается, время в «Решена» не считается |
| `cancelled` | Счётчики отменяются |

- Реакция завершается моментом `ticket.firstResponseAt`.
- Смена приоритета пересчитывает срок от исходного старта, журнал хранит старый и новый срок (SLA-06).
  Если по новой цели нарушения ещё нет, оно снимается.
- Смена условий (услуга, клиент, группа) отменяет счётчик старой политики и запускает новую политику с тем же
  стартом и паузами. OLA с условием по группе стартует в момент назначения на группу.
- Снимок старше уже обработанного отбрасывается, порядок доставки событий не важен.
- После каждого пересчёта модуль вызывает `TicketCommands.updateSlaDue(ticketId, ближайший срок)`:
  сначала по SLA, если его нет — по OLA и UC; на паузе или после выполнения — `null`.

## События

| Событие | Когда | Поля |
| --- | --- | --- |
| `SlaWarning` | достигнут порог | `percent`, `reachedAt` (точное время), `dueAt`, `actions` |
| `SlaBreached` | срок истёк, один раз на счётчик | `breachedAt` (= срок), `priority`, `actions` |

Действия — строки: `notify:assignee|group|group_lead|requester|watchers` (выполняет модуль уведомлений),
`escalate:<groupId>` и `escalate:hierarchical` (выполняет sla через `TicketCommands.escalate`),
`set_priority:P1` (выполнит автоматизация).

## Java API

```java
List<SlaInstanceView> SlaQueries.forTicket(ticketId)                 // карточка, портал
Map<UUID, List<SlaInstanceView>> SlaQueries.activeForTickets(ids)    // строки очереди
UUID SlaSetup.installDefaults(ZoneId)                                 // мастер настройки: раздел 5.3
```

## REST

| Метод | Роли |
| --- | --- |
| `GET /sla/instances?ticketId=`, `GET /sla/instances/{id}` (паузы и история) | AGT, LEAD, MIM, CHG, PRB, ANL, AUD, ADM |
| `GET /sla/schedules`, `/{id}`, `/{id}/holidays?year=` | ADM, LEAD, ANL, AUD |
| `POST /sla/schedules`, `PATCH /{id}` (If-Match), `PUT /{id}/holidays?year=` | ADM |
| `GET /sla/policies`, `/{id}`, `POST /sla/policies/{id}/preview` | ADM, LEAD, ANL, AUD |
| `POST /sla/policies`, `PATCH /{id}` (If-Match), `POST /sla/setup/defaults` | ADM |

Изменение графика или праздников пересчитывает идущие счётчики фоновым обработчиком. Изменение целей политики
действует на новые счётчики.

## Фоновый обработчик

`SlaTimerJob` в роли worker раз в 10 с (`easyitsm.sla.timer-interval`) обрабатывает счётчики с наступившей
`next_check_at` во всех арендаторах через функцию `sla_due_instances`. Метрика отставания —
`easyitsm.sla.timer.lag` (оповещение при 60 с, раздел 12.5).
