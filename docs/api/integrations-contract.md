# Контракт модуля интеграций (integrations): мониторинг

Java: своего API и событий пока нет, модуль действует через `tickets.api`. REST:
`POST /api/v1/integrations/events` (система мониторинга, вход по токену источника),
`/api/v1/integrations/monitoring/**` (ADM, чтение — ещё AUD). Экран — «Управление → Интеграции».

## Приём событий (раздел 8.4 ТЗ, INC-01)

```
POST /api/v1/integrations/events
Authorization: Bearer eitsm_mon_…        // токен источника, в БД хранится только SHA-256
Content-Type: application/json
```

Формат тела задаёт вид источника:

| Вид | Что присылает | Отпечаток (fingerprint) |
| --- | --- | --- |
| `zabbix` | скрипт `docs/integrations/zabbix-webhook.js`: `event_id`, `trigger_id`, `status` PROBLEM / RESOLVED / UPDATE, `severity`, `name`, `host`, `message`, `url`, `tags` | `dedup_key`, иначе `trigger:<trigger_id>:<host>`, иначе `event:<event_id>` |
| `alertmanager` | вебхук Alertmanager v4 и Grafana Alerting: `alerts[]` с `status`, `labels`, `annotations`, `fingerprint` | `fingerprint` алерта, иначе SHA-256 меток |
| `generic` | объект или `{"events": [...]}`: `fingerprint`, `title`, `status` firing / resolved, `severity`, `description`, `host`, `url`, `details` | `fingerprint` |

Важность приводится к `critical | high | warning | info` (Zabbix Disaster → critical, High → high, Average и
Warning → warning, Information и Not classified → info; метка `severity` Alertmanager — так же по смыслу).
Ссылки принимаются только `http(s)`.

Ответ `202`:

```json
{"accepted": 2, "skipped": 0, "results": [
  {"fingerprint": "abc", "outcome": "created", "ticket": "INC-000123"},
  {"fingerprint": "def", "outcome": "ignored", "detail": "важность ниже порога источника"}]}
```

Ошибки: `401 invalid_token`, `403 source_disabled`, `413 payload_too_large`
(`easyitsm.integrations.max-body-bytes`, 1 МБ), `429 rate_limited` (`rate-per-minute`, 600 на под),
`400 invalid_event` — тело не разобрано, запись `error` в журнале источника. В одном запросе не больше
`max-alerts-per-event` (200) алертов, остальные пропускаются с записью в журнале.

## Что происходит с инцидентом

Один активный (firing) отпечаток — один инцидент. Одновременные события одного отпечатка упорядочиваются
рекомендательной блокировкой, второй инцидент не появится.

| Событие | Состояние | Итог (`outcome`) |
| --- | --- | --- |
| проблема | отпечатка нет, важность не ниже `minSeverity` | `created`: инцидент, канал `monitoring`, заявитель — служебный пользователь источника, влияние и срочность — по `severityMap`, группа — `groupId` или маршрутизация |
| проблема | важность ниже порога | `ignored` |
| проблема | инцидент открыт, важность та же или ниже | `updated`: только счётчик повторов |
| проблема | инцидент открыт, важность выросла | `escalated`: влияние и срочность поднимаются (`TicketCommands.change`), внутренняя заметка |
| проблема | инцидент решён | `reopened`: публичный ответ заявителя возвращает его в работу (INC-08) |
| проблема | инцидент закрыт или отменён | прежний алерт завершается, `created` — новый инцидент |
| устранено | инцидент в работе, `autoResolve` | `resolved`: `TicketCommands.resolveIfPossible` с кодом `resolutionCode` |
| устранено | инцидент новый или без исполнителя | `resolved` с внутренней заметкой «проблема устранена» |
| устранено | активного отпечатка нет | `ignored` |

Ошибка одного алерта (`error`) не мешает остальным алертам запроса.

## Настройки источников

```
GET    /integrations/monitoring/sources                 // список SourceView: source, requesterName, groupName
POST   /integrations/monitoring/sources                 // 201 {source: SourceView, token}
GET    /integrations/monitoring/sources/{id}            // ETag
PATCH  /integrations/monitoring/sources/{id}            // If-Match; clearGroup: true снимает группу
POST   /integrations/monitoring/sources/{id}/token      // перевыпуск, SEC-08; старый токен сразу недействителен
DELETE /integrations/monitoring/sources/{id}            // SEC-08; удаляет журнал и алерты источника
GET    /integrations/monitoring/sources/{id}/alerts     // активные алерты
GET    /integrations/monitoring/events?sourceId=&outcome=&cursor=&limit=
```

Поля источника: `name`, `kind`, `enabled`, `requesterId` (без него при создании заводится пользователь
«Мониторинг: имя» без почты и пароля), `groupId`, `minSeverity` (по умолчанию warning), `severityMap`
(`{"critical": {"impact": "high", "urgency": "high"}, …}`), `autoResolve` (true), `resolutionCode` (`fixed`,
из активных кодов решения «Управления»). Токен показывается только в ответах создания и перевыпуска, в списке —
`tokenHint` из последних символов. Ошибки проверки — `400 invalid_source`.

Изменения пишутся в журнал версий настроек (`integrations.monitoring_source`, без токена) и в журнал аудита
как настройки раздела `integrations`. Журнал событий хранится `keep-events-days` (30) дней.

## Подключение

- **Zabbix 6.0+**: тип оповещения Webhook со скриптом `docs/integrations/zabbix-webhook.js` и параметрами
  с экрана источника; глобальные макросы `{$EASYITSM.URL}`, `{$EASYITSM.TOKEN}`, `{$ZABBIX.URL}`. Действие на
  триггеры с операциями проблемы и восстановления.
- **Alertmanager**: получатель `webhook_configs` с `send_resolved: true` и `http_config.authorization`
  (тип Bearer, токен).
- **Grafana Alerting**: точка контакта Webhook, заголовок авторизации Bearer.
