# Контракт модуля CMDB (cmdb)

Java: `ru.easyitsm.cmdb.api` и `ru.easyitsm.cmdb.events`. REST: `/api/v1/cmdb/**`, схема — [`cmdb.yaml`](cmdb.yaml).
Требования: CFG-01…09 (раздел 4.9 ТЗ), таблицы — раздел 7.5, экраны — 10.13.
Контракт стабилен в рамках `/api/v1`: поля добавляются, но не удаляются и не меняют смысл.

## Модель

- **Класс КЕ** (`ci_class`, CFG-01): иерархия с наследованием. Подкласс получает атрибуты предков и может
  переопределить атрибут по коду. Правила идентификации — свои или ближайшего предка. Абстрактный класс
  (например, `infrastructure`) служит для группировки, КЕ в нём не создаются.
- **Уровень сервисной модели** (`layer`, CFG-02) задаёт класс: `business_service`, `technical_service`,
  `application`, `infrastructure`. **Услуга** — это КЕ уровня `business_service` или `technical_service`,
  её id и есть id услуги (поле `serviceId` у заявок, статей, объявлений). Отдельной таблицы услуг нет.
- Классы по умолчанию (создаются для арендатора при первом обращении, системные нельзя удалить):

  | Код | Название | Уровень | Идентификация |
  | --- | --- | --- | --- |
  | `business_service` | Бизнес-услуга | business_service | — |
  | `technical_service` | Техническая услуга | technical_service | — |
  | `application` → `database` | Приложение, база данных | application | `url` |
  | `infrastructure` (абстрактный) | Инфраструктура | infrastructure | `serial_number` |
  | `server` → `physical_server`, `virtual_machine` | Серверы | infrastructure | `serial_number` / `vm_uuid`, `fqdn`, `hostname`+`domain` |
  | `network_device`, `storage`, `workstation`, `k8s_cluster`, `cloud_resource` | | infrastructure | по классу |

- **Атрибуты** КЕ — JSON по схеме класса: `string`, `text`, `number`, `boolean`, `date`, `ip`, `enum`.
  Неизвестный атрибут, неверный тип, пустой обязательный — 400 с `field`. Идентификаторы хранятся в одном
  написании: `hostname`, `domain`, `fqdn`, `url` строчными, `serial_number` прописными, MAC как `AA:BB:…`.
  У каждого атрибута записан источник значения (`attributeSources`), по нему работает сверка.
- **CFG-08:** пароли, токены, ключи в имени, описании и атрибутах КЕ запрещены — 400 `secret_not_allowed` с `field`,
  значение не сохраняется и не попадает в ответ. Поиск секретов — общий `tickets.api.SecretScanner`.
- **Статус:** `planned`, `active`, `maintenance`, `retired`. Удаления нет: КЕ выводится из эксплуатации или
  сливается с дубликатом при сверке (событие `CiMerged`).
- **Связи** (CFG-03) типизированы. Тип задаёт прямое и обратное название и направление влияния:

  | Код | Прямое | Обратное | Влияние |
  | --- | --- | --- | --- |
  | `depends_on` | Зависит от | Нужна для | отказ цели влияет на источник |
  | `runs_on` | Работает на | Выполняет | отказ цели влияет на источник |
  | `hosts` | Размещает | Размещена на | отказ источника влияет на цель |
  | `contains` | Содержит | Входит в | отказ источника влияет на цель |
  | `connected_to` | Связана с | Связана с | не влияет |

  Снятая связь не удаляется, а получает `valid_to`: так строится состояние на дату (CFG-06).
- **История** (`ci_history`, CFG-06): каждое изменённое поле (`name`, `class`, `status`, `environment`,
  `criticality`, `owner_group`, `description`, `attr.<код>`, `relation`) со старым и новым значением, источником
  и автором.

## Анализ влияния (CFG-09, CHG-06)

Обход в ширину от исходных КЕ по активным связям в направлении влияния, глубина 1–5 (по умолчанию 3 в REST),
до 2000 КЕ (`truncated`). Каждая КЕ входит один раз с кратчайшим путём. Выведенные из эксплуатации КЕ влияние
не передают. Пример: сервер ← (runs_on) приложение ← (depends_on) техническая услуга ← (depends_on) бизнес-услуга:
отказ сервера затрагивает приложение (1), техуслугу (2) и бизнес-услугу (3).

Открытые заявки — те, у которых `ciId` или `serviceId` равен исходной или затронутой КЕ, категория не
`draft`, `resolved`, `closed`, `cancelled` (`tickets.api.TicketQueries.openForObjects`). REST показывает только
видимые пользователю заявки, остальные — числом `hiddenTickets`.

## Права (раздел 3)

| Действие | Роли |
| --- | --- |
| Читать КЕ, связи, классы, влияние | `AGT`, `LEAD`, `MIM`, `CHG`, `PRB`, `CFG`, `AST`, `ADM`, `AUD` |
| Создать и изменить КЕ, связи | `CFG` |
| Настроить классы, типы связей, источники (PR 2–3) | `CFG`, `ADM` |
| Загрузить данные из источника (PR 3) | `SVC` (сервисная учётная запись), `CFG` |

## Java API для модулей

Без проверки прав пользователя, в арендаторе вызывающего. Отсутствующая или чужая КЕ — пусто.

```java
Optional<CiRef> CiDirectory.find(ciId)
List<CiRef>     CiDirectory.findAll(ciIds)          // в порядке запроса, до 1000
List<CiRef>     CiDirectory.search(CiSearch)        // CiSearch.text(q, limit), CiSearch.services(q, limit)
ImpactReport    CiImpact.analyze(ciIds, depth)      // roots, impacted, services, openTicketIds, truncated
List<ImpactedCi> CiImpact.impactedServices(ciIds, depth)

CiRef(id, name, classCode, className, layer, status, environment, criticality, ownerGroupId, version)
      .isService()
ImpactedCi(ci, depth, path)   // path: id от исходной КЕ до этой
```

Фильтр `CiSearch.classCodes` включает подклассы: `server` найдёт и `virtual_machine`.

## События

| Событие | Когда | Поля |
| --- | --- | --- |
| `CiCreated` | КЕ создана вручную или из источника | `ci`, `source` |
| `CiUpdated` | изменены поля, кроме статуса (`ci.updated`) | `ci`, `fields`, `source` |
| `CiStatusChanged` | сменился статус; вывод из эксплуатации — `ci.status() == RETIRED` | `ci`, `previous` |
| `CiMerged` | дубликат слит при сверке (PR 3): ссылки с `mergedId` перевести на `ci.id()` | `ci`, `mergedId` |
| `CiImpactChanged` | изменился граф влияния (`ci.impact_changed`): связь добавлена или снята, КЕ выведена или слита | `ciIds`, `reason` |

Общие поля: `tenantId`, `occurredAt`, `actorId` (null для системы и источников), `source` (`manual` или код
источника). Модуль cmdb зависит от `tickets.api` и `identity.api`; изменения, проблемы и активы зависят от cmdb,
а не наоборот: вкладка «Изменения» на карточке КЕ читает REST изменений `GET /api/v1/changes?ciId=`.

## REST

| Метод и путь | Назначение |
| --- | --- |
| `GET /cmdb/classes` | Классы с унаследованными атрибутами (`attributes`) и собственными (`ownAttributes`) |
| `GET /cmdb/relation-types` | Типы связей |
| `GET /cmdb/cis` | Список: `q`, `classCode` (с подклассами), `layer`, `status`, `environment`, `criticality` (через запятую), `ownerGroupId`, `includeRetired`, курсор |
| `POST /cmdb/cis` | Создать КЕ |
| `GET, PATCH /cmdb/cis/{id}` | Карточка с `ETag`; изменение — JSON Merge Patch с `If-Match`, `null` очищает поле или атрибут |
| `GET, POST /cmdb/cis/{id}/relations` | Связи КЕ с её стороны: `direction` `outgoing` / `incoming`, подпись `label` |
| `DELETE /cmdb/relations/{id}` | Снять связь (остаётся в истории) |
| `GET /cmdb/cis/{id}/impact?depth=` | Анализ влияния с видимыми заявками |
| `GET /cmdb/cis/{id}?at=`, `GET /cmdb/cis/{id}/relations?at=` | Состояние на момент времени (CFG-06), без ETag |
| `GET /cmdb/cis/{id}/history` | История полей и связей, новые первыми, курсор |
| `GET /cmdb/cis/{id}/map?depth=&type=` | Карта: соседи в обе стороны до 5 уровней (по умолчанию 2), до 300 КЕ, фильтр типов связи, на узлах число открытых инцидентов и изменений (по `ciId`/`serviceId` заявки) |
| `POST /cmdb/classes`, `PATCH /cmdb/classes/{code}` | Свои классы и атрибуты (CFG, ADM); код и родитель неизменны, у стандартных классов атрибуты можно только добавлять |
| `POST /cmdb/relation-types`, `PATCH /cmdb/relation-types/{code}` | Свои типы связей; у стандартных меняются только названия |
| `GET /cmdb/quality` | CFG-07: всего КЕ, неполные, неактуальные, сироты, группы дубликатов, оценка 0–100 |
| `GET /cmdb/quality/issues?kind=incomplete\|stale\|orphan`, `GET /cmdb/quality/duplicates` | Списки для экрана «Качество», до 200 |

Показатели качества считаются по КЕ не в статусе `retired`: **неполная** — нет группы-владельца или
обязательного атрибута; **неактуальная** — источник не видел её 30 дней, а КЕ без источника не менялась год;
**сирота** — без активных связей; **дубликаты** — одинаковое значение атрибута из правила идентификации из одного
атрибута (`serial_number`, `fqdn`, `vm_uuid`…) или одинаковое имя в классе. Имена атрибутов, похожие на секрет
(`password`, `token`, `api_key`…), в схеме класса запрещены (CFG-08). Изменения классов и типов связей пишет
журнал аудита (`AuditRoutes`).

## Загрузка из источников (CFG-04, CFG-05) — проект, PR 3

Один API для агента обнаружения (Go), импорта CSV, коннекторов Kubernetes и облаков. Источник
(`ci_source`) заводит CFG: код, вид (`import`, `api`, `discovery`, `kubernetes`, `cloud`), приоритет (1 — самый
доверенный; ручной ввод `manual` по умолчанию 10) и срок актуальности в днях.

```http
POST /api/v1/cmdb/sources/{source}/batches
Authorization: Bearer <сервисный токен, роль SVC, область cmdb:ingest>
Content-Type: application/json

{
  "batchId": "scan-2026-11-06T12:00Z-001",   // уникален в источнике; повтор вернёт тот же результат
  "runId": "scan-2026-11-06T12:00Z",         // пакеты одного полного обхода
  "final": false,                             // последний пакет обхода: невиденные КЕ источника — «неактуальные»
  "items": [{
    "externalId": "vm-42",                    // стабильный ключ в источнике
    "classCode": "virtual_machine",
    "name": "app-01",
    "status": "active", "environment": "production",
    "attributes": {"hostname": "app-01", "domain": "corp.local", "vm_uuid": "4c4c4544-…"},
    "relations": [{"typeCode": "runs_on", "direction": "outgoing", "target": {"externalId": "esx-3"}}]
  }],
  "removed": ["vm-17"]                        // источник больше не видит эти объекты
}
```

Ответ `200` (и `GET /cmdb/sources/{source}/batches/{batchId}`):

```json
{"batchId": "scan-…-001", "status": "done", "received": 1, "created": 0, "updated": 1, "unchanged": 0,
 "conflicts": 0, "errors": 0,
 "results": [{"externalId": "vm-42", "ciId": "…", "outcome": "updated"}]}
```

- До 1000 объектов и 5 МБ в пакете; пакет обрабатывается синхронно, объекты независимы: ошибка одного
  (`outcome: error` с `code` и `field`, например `secret_not_allowed`) не мешает остальным.
- Идентификация: сначала связка (источник, `externalId`), затем правила класса по порядку среди КЕ того же
  корневого класса. Одна КЕ — совпадение; несколько — конфликт `duplicate`, объект пропускается; ни одной —
  новая КЕ.
- Приоритет: значение атрибута из источника заменяет текущее, если атрибут пуст, получен от этого же источника
  или от источника с приоритетом не выше. Иначе при расхождении — конфликт `attribute` на экране «Сверка» с
  действиями «Принять источник», «Игнорировать» (повторно не поднимается, пока значение не изменится),
  а для дубликатов — «Объединить» (событие `CiMerged`).
- `relations` в объекте заменяют связи этого источника от этой КЕ; ручные связи и связи других источников не
  трогаются. Цель — `externalId` того же источника или `ciId`.
- `last_seen_at` КЕ обновляется при каждом появлении в пакете (CFG-07, актуальность).
