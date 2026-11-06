# Контракт модуля «Управление» (admin)

ADM-01…04, 06, раздел 10.16 ТЗ. Пакет `ru.easyitsm.admin`, миграция `db/migration/admin/V2026_11_06_0743__admin_init.sql`.

Модуль владеет только тем, чего нет у других: состояние мастера, организации и подразделения, коды причин, дежурства,
версии настроек и импорт CSV. Пользователи и группы меняются через `identity.api.DirectoryAdmin`, SLA — через
`sla.api.SlaSetup`, заявки — через `tickets.api.TicketCommands`. Экраны «Управления» для настроек других модулей
вызывают их REST напрямую и после сохранения записывают версию в `/admin/config-versions`.

## Java API для других модулей

| Интерфейс | Что даёт |
| --- | --- |
| `ReasonCodes.active(Kind)` | Активные коды решения, ожидания, отмены в порядке администратора. Коды по умолчанию совпадают с прежними константами рабочего места агента; `workspace` уже читает их отсюда. |
| `OrgUnits` | Организации и подразделения (`organization_id`, `department_id` пользователя). |
| `DutyRoster.onDuty(groupId, at)` | Дежурные группы в момент времени — для маршрутизации и уведомлений. |
| `ConfigHistory.record(...)` | Модуль может сам записать версию своей настройки (сервер, `source = server`). |

Событие `admin.events.ConfigChanged(tenantId, versionId, entity, entityId, versionNo, action, actorId, at)` — для
модуля аудита: каждая версия настройки и действие администратора. Снимок в событие не кладётся.

## REST `/api/v1/admin`

| Путь | Права | Назначение |
| --- | --- | --- |
| `GET/PUT setup`, `PUT setup/organization` (If-Match), `PUT setup/steps/{step}`, `POST setup/complete` | ADM | Мастер. Шаги: organization, login, mail, groups, services, sla, demo; состояния pending, done, skipped. |
| `POST setup/checks/mail`, `POST setup/checks/sso` | ADM | Проверки по адресам из настроек поставки (`spring.mail.*`, OIDC-регистрации), адрес из запроса не принимается. |
| `POST setup/demo-data` | ADM | Демоданные один раз (повтор — 409 `demo_already_loaded`). |
| `GET/PUT dictionaries/{resolution,pending,cancel}` | читают сотрудники, меняет ADM | Сохранённый код удалить нельзя (409 `code_removed`), только выключить. |
| `GET/POST/PATCH org-units` | читают сотрудники, меняет ADM | Дерево без циклов, имя уникально у одного родителя. |
| `GET/POST/DELETE duty-shifts` | читают сотрудники, меняет ADM | Дежурит только участник группы, смена до 31 дня. |
| `GET config-versions`, `GET {id}`, `GET {id}/diff?against=` | ADM, SEC, AUD | Журнал действий и сравнение версий. |
| `POST config-versions` | ADM, SEC | Версия настройки другого модуля (`source = ui`). Виды `admin.*` записывает только сервер. |
| `GET/POST imports`, `POST imports/{id}/apply?skipInvalid=`, `POST imports/{id}/rollback` | ADM | Импорт CSV users, groups, departments: предпросмотр, применение одной транзакцией, откат. |

Виды настроек в журнале: `admin.setup`, `admin.dictionary`, `admin.org_unit`, `admin.duty`, `admin.import`,
`identity.user`, `identity.group`, `sla.schedule`, `sla.holidays`, `tickets.category`, `tickets.workflow`,
`tickets.priority_matrix`.

Откат импорта удаляет созданное в обратном порядке. Пользователь, который уже входил, не удаляется, а блокируется.
