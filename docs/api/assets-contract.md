# Контракт модуля активов (assets)

Java: `ru.easyitsm.assets.api` и `ru.easyitsm.assets.events`. REST: `/api/v1/assets/**`, `/api/v1/licenses/**`,
`/api/v1/contracts/**`, `/api/v1/vendors/**`, `/api/v1/asset-locations/**`, `/api/v1/me/assets`, `/api/v1/me/licenses`.
Требования ТЗ: AST-01, AST-03, AST-04, AST-05, экран 10.14.

## Данные

| Таблица | Что хранит |
| --- | --- |
| `asset` | Оборудование: инвентарный номер (уникален в арендаторе без учёта регистра), тип, производитель, модель, серийный номер, состояние, сотрудник, местоположение, КЕ, поставщик, договор, покупка, гарантия, срок службы, стоимость |
| `asset_event` | История актива, только дописывается |
| `asset_ticket` | Связь актива с заявкой |
| `asset_location` | Местоположения: офис, этаж, склад |
| `asset_vendor` | Поставщики |
| `asset_contract` | Договоры со сроками и напоминанием о продлении |
| `software_license` | Лицензии ПО: продукт, метрика, количество, срок действия. Ключи лицензий не хранятся |
| `license_assignment` | Использование лицензии: устройство или сотрудник |

Сотрудник, КЕ и заявка хранятся идентификаторами. Имена читаются через `identity.api.UserDirectory`,
`tickets.api.TicketQueries` и (после подключения) `cmdb.api.CiDirectory`. Модули identity, tickets и cmdb об активах
не знают.

## Жизненный цикл оборудования

Состояния: `in_stock` (на складе), `in_use` (выдан), `in_repair` (в ремонте), `retired` (списан).

| Действие | REST | Откуда | Куда | Сотрудник |
| --- | --- | --- | --- | --- |
| Выдать | `POST /assets/{id}/issue` `{holderId, locationId?, comment?}` | на складе, в ремонте | выдан | обязателен, действующий |
| Передать | `POST /assets/{id}/transfer` `{holderId, …}` | выдан | выдан | новый |
| В ремонт | `POST /assets/{id}/repair` `{locationId?, comment?}` | на складе, выдан | в ремонте | остаётся |
| Вернуть | `POST /assets/{id}/return` `{locationId?, comment?}` | выдан, в ремонте | на складе | снимается |
| Списать | `POST /assets/{id}/retire` `{reason}` | любое, кроме списан | списан | снимается, лицензии устройства освобождаются |

Все действия и `PATCH` требуют `If-Match`. Недопустимый переход — 409 `invalid_state`. Списанный актив не
изменяется. Каждое действие пишет запись в историю (`GET /assets/{id}/history`).

`PATCH` частичный: `null` — не менять, поля из массива `clear` очищаются (например `"clear": ["serial", "ciId"]`).

## Права

| Роль | Может |
| --- | --- |
| `AST`, `ADM` | Всё: активы, лицензии, договоры, поставщики, местоположения |
| `AGT`, `LEAD` | Читать и связывать актив с заявкой, которую видит |
| `CFG`, `AUD` | Читать |
| Любой вошедший | `/me/assets`, `/me/licenses` — только закреплённое за ним |

Связь с заявкой (`POST /assets/{id}/tickets` `{ticketId | number}`, `DELETE /assets/{id}/tickets/{ticketId}`,
фильтр `GET /assets?ticketId=`) проверяет видимость заявки правилами модуля заявок: невидимая — 404. В истории
актива номер и тема невидимой заявки не раскрываются.

## Лицензии

Метрики: `device`, `user`, `concurrent`, `site`, `subscription`. Для `device`, `user` и `subscription` с
количеством назначение сверх количества — 409 `license_exhausted`. Ответ показывает `used`, `available` и
`overused` (например, если количество уменьшили). `quantity = null` — без ограничения.

## Напоминания

Задача worker `RenewalJob` (раз в час, `easyitsm.assets.renewal-check-interval`) берёт из `assets_renewal_queue`
договоры и лицензии, у которых `дата окончания − renewal_notice_days ≤ сегодня`, и:

- публикует `ContractExpiring` или `LicenseExpiring`;
- отправляет ответственному (`ownerId`) уведомление в центр и письмо.

Напоминание уходит один раз на каждую дату окончания: после продления (новая дата) придёт снова.

## Java API

```java
AssetQueries.heldBy(userId)          // оборудование сотрудника (не списанное)
AssetQueries.linkedToCi(ciId)        // активы КЕ
AssetQueries.linkedToTicket(ticketId)
```

Без проверки прав пользователя, в арендаторе вызывающего.

## События

| Событие | Когда |
| --- | --- |
| `AssetAssigned(tenantId, occurredAt, actorId, assetId, tag, name, holderId, previousHolderId)` | Выдача или передача. Сотрудник получает уведомление в центре |
| `AssetRetired(tenantId, occurredAt, actorId, assetId, tag, reason)` | Списание |
| `ContractExpiring(tenantId, contractId, number, title, endsOn, daysLeft, autoRenew, ownerId)` | Срок напоминания о продлении договора |
| `LicenseExpiring(tenantId, licenseId, product, validTo, daysLeft, ownerId)` | Срок напоминания об окончании лицензии |

## Связь с CMDB (AST-05)

`asset.ci_id` — ссылка на КЕ. Пока модуль cmdb не в `main`, ссылка сохраняется без проверки и показывается без
имени. После слияния cmdb модуль активов проверяет КЕ через `CiDirectory`, показывает имя и класс, переводит
ссылку на выжившую КЕ по `CiMerged`.
