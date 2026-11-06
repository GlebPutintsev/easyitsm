# Контракт модуля интеграций (integrations): мониторинг и исходящие вебхуки

Java: `integrations.api.Webhooks` — список включённых вебхуков и постановка события в очередь доставки
(им пользуется автоматизация); входящие события действуют через `tickets.api`. REST:
`POST /api/v1/integrations/events` (система мониторинга, вход по токену источника),
`/api/v1/integrations/monitoring/**`, `/webhooks/**`, `/outbound-policy`, `/webhook-deliveries/**`
(ADM, чтение — ещё AUD). Экран — «Управление → Интеграции».

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

## Исходящие вебхуки (раздел 8.3 и 8.5 ТЗ, AUT-05)

Вебхук — адрес получателя и ключ подписи. События в него отправляют правила автоматизации действием
`webhook`, отдельной подписки на события нет.

```
GET    /integrations/webhooks                       // список, без ключа
POST   /integrations/webhooks                       // 201 {webhook, secret}: ключ whsec_… показывается один раз
GET    /integrations/webhooks/{id}                  // ETag
PATCH  /integrations/webhooks/{id}                  // If-Match: name, url, enabled
POST   /integrations/webhooks/{id}/secret           // перевыпуск ключа, SEC-08
DELETE /integrations/webhooks/{id}                  // SEC-08, вместе с журналом доставок
POST   /integrations/webhooks/{id}/test             // webhook.test сразу, без очереди: {ok, status, error, response}
GET    /integrations/outbound-policy                // {allowedHosts, version}, ETag
PUT    /integrations/outbound-policy                // If-Match; 400 invalid_policy
GET    /integrations/webhook-deliveries?webhookId=&status=pending|delivered|dead&cursor=&limit=
GET    /integrations/webhook-deliveries/{id}        // {delivery, payload}
POST   /integrations/webhook-deliveries/{id}/retry  // только dead: попытки сначала; иначе 409 not_failed
```

Запрос получателю — `POST`, `Content-Type: application/json`:

```
X-EasyITSM-Event: automation.rule
X-EasyITSM-Delivery: <id доставки — для защиты от повторной обработки>
X-EasyITSM-Timestamp: <unix-время, секунды>
X-EasyITSM-Signature: sha256=<hex(HMAC-SHA256(ключ, timestamp + "." + тело))>

{"id": "…", "type": "automation.rule", "schema_version": 1, "occurred_at": "…", "tenant": "…",
 "data": {"rule": "…", "trigger": "ticket_created",
          "ticket": {"id", "number", "type", "title", "confidential", "status", "status_category", "priority",
                     "impact", "urgency", "channel", "group_id", "assignee_id", "requester_id", "due_at",
                     "created_at", "url"}}}
```

У конфиденциальной заявки `title` — `null`. Описание и комментарии не передаются. Получатель проверяет подпись
и отбрасывает запросы со временем старше 5 минут.

Доставка: ответ 2xx — `delivered`. Иначе повтор через 1, 2, 4 … минуты, всего `outbound.max-attempts` (9)
попыток, затем `dead`; ADM повторяет вручную. Повтор шлёт те же байты тела. Перенаправления — только 307 и 308,
не больше трёх, каждый адрес проверяется заново. Таймаут — `outbound.timeout` (10 с). Доставки старше
`keep-events-days` удаляются вместе с журналом мониторинга.

### Разрешённые адреса (защита от SSRF)

Пока список ADM пуст, вебхуки никуда не отправляются. Правило — имя узла (`hooks.example.ru`), маска
(`*.example.ru`) или сеть CIDR (`10.20.0.0/16`). Перед каждым запросом адрес узла разрешается и проверяется:

- всегда запрещены служебные адреса (0.0.0.0, link-local 169.254.0.0/16 и fe80::/10 вместе с метаданными
  облака, multicast) и адреса самого сервера (loopback; для разработки — `outbound.allow-loopback`);
- внутренние сети (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7) — только по точному имени узла или сети
  CIDR, маска `*.домен` туда не пропускает;
- логин и пароль в адресе не допускаются, схемы — только http и https.

Маска доверяет DNS домена: между проверкой и соединением адрес может смениться. Для внутренних получателей
указывайте точное имя или сеть, а в Kubernetes дополнительно ограничьте исходящий трафик `NetworkPolicy`.

Изменения вебхуков и списка пишутся в журнал версий настроек (`integrations.webhook`,
`integrations.outbound_policy`, без ключа) и в журнал аудита как настройки раздела `integrations`.
