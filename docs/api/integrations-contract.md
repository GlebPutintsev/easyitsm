# Контракт модуля интеграций (integrations): мониторинг, исходящие вебхуки и мессенджеры

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

## Мессенджеры Telegram, MAX, Slack и Teams (раздел 8.2 ТЗ, CHN-04, CHN-06)

ADM подключает бота на вкладке «Интеграции → Мессенджеры»: токен (хранится зашифрованным, наружу — только
последние 4 символа), адрес API (пусто — официальный: `api.telegram.org` или `platform-api.max.ru`), можно ли
создавать заявки, тип и группа новых заявок и перечень передаваемых данных. При сохранении сервер вызывает
`getMe` (`GET /me` у MAX) и снимает вебхук Telegram; бот, который не отвечает, не сохраняется
(`400 messenger_unreachable`). Официальный адрес разрешён всегда, свой адрес (прокси) — только из списка
разрешённых адресов, с теми же проверками SSRF, что у вебхуков.

```
GET    /api/v1/integrations/messengers            ADM, AUD
POST   /api/v1/integrations/messengers            ADM   {kind, name, token, appToken?, appId?, appTenant?, baseUrl?, allowTickets, ticketType, groupId?, dataFields}
PATCH  /api/v1/integrations/messengers/{id}       ADM   If-Match; token — только для замены, clearGroup: true снимает группу
DELETE /api/v1/integrations/messengers/{id}       ADM   повторный вход; удаляет привязки и очередь
POST   /api/v1/integrations/messengers/{id}/check ADM   {ok, botUsername, error}
GET    /api/v1/integrations/my-messengers         любой вошедший: боты и своя привязка
POST   /api/v1/integrations/my-messengers/{id}/link  {url, code, command, expiresAt} — одноразовый код на 15 минут
DELETE /api/v1/integrations/my-messengers/{id}    отвязать себя
```

Сервер сам опрашивает мессенджер (`getUpdates` / `GET /updates`) раз в `messenger-interval` (5 с), поэтому
входящий доступ из интернета не нужен. Опрос одного бота идёт на одном поде (advisory-блокировка), каждое
сообщение обрабатывается в своей транзакции. Принимаются только личные чаты.

Привязка: пользователь в профиле получает ссылку `t.me/<бот>?start=<код>` (`max.ru/<бот>?start=`) и нажимает
«Старт». В базе хранится только SHA-256 кода. Один чат — один пользователь; повторная привязка заменяет
прежнюю. `/stop` в чате отвязывает.

Что делает бот:

- новое сообщение от привязанного пользователя — заявка с каналом `chat`; тема — первая строка (до 120
  символов), описание — весь текст. Не больше 10 заявок в час из одного чата. Если создание выключено, бот
  отвечает, что принимает только ответы;
- ответ на сообщение бота о заявке — комментарий: публичный от заявителя, внутренний от исполнителя или
  наблюдателя; остальным бот отказывает;
- непривязанному чату — подсказка, где взять ссылку; `/help` — справка.

Уведомления (NTF-01) идут в привязанные боты вместе с центром уведомлений: модуль notifications передаёт
каждое уведомление в `ExternalChannel`, а модуль интеграций ставит сообщение в очередь
`integration_messenger_outbox`. Номер заявки передаётся всегда; тема и название — если в перечне есть `title`,
текст — `text`, ссылка — `link` (по умолчанию `title` и `link`). Конфиденциальная заявка приходит уже
замаскированной (NTF-05). Отправка повторяется через 1, 2, 4, 8 минут, всего 5 попыток, затем `dead`
с записью в журнал сервера. Ошибки опроса видны в «Состоянии» бота. Отправленное сообщение запоминается за заявкой, чтобы ответ на него стал
комментарием. Очередь, коды и связи сообщений удаляются через `keep-events-days`.

### Slack

Slack подключается через Socket Mode: сервер сам открывает WebSocket к Slack, поэтому входящий доступ из
интернета не нужен, как и у Telegram и MAX. Кроме токена бота (`xoxb-`, права `chat:write`, `im:history`,
`im:read`) ADM указывает токен приложения (`xapp-`, право `connections:write`), включает Socket Mode, подписку на
событие `message.im` и сообщения на вкладке App Home. Оба токена хранятся зашифрованными; при сохранении сервер
вызывает `auth.test` и `apps.connections.open`.

- Каждый worker держит своё соединение (Slack раздаёт события между соединениями приложения, их до 10).
  Соединение принимается только по адресу `wss://*.slack.com` с проверкой узла; при ошибке повтор через 30 с,
  1, 2 … до 10 минут. Состояние соединения видно в «Состоянии» бота.
- Конверт события подтверждается сразу, затем сообщение обрабатывается в своей транзакции. Повторная
  доставка того же сообщения (Slack повторяет, опрос после сбоя пода) не создаёт вторую заявку и комментарий.
- Привязка: в профиле — ссылка на бота в рабочем пространстве и код, который пользователь отправляет боту
  личным сообщением (`/start` в Slack перехватывает сам Slack). Команды — `help` и `stop` без косой черты.
- Ответы бота и уведомления о заявке идут в ветке сообщения; ответ в ветке — комментарий к заявке.

### Microsoft Teams

Teams работает через Azure Bot Service. В отличие от остальных мессенджеров, сообщения присылает сам Bot
Framework, поэтому серверу нужен **публичный HTTPS-адрес**; за NAT без публикации Teams не работает.

ADM регистрирует бота в Azure (Azure Bot, тип Single Tenant), включает канал Microsoft Teams и указывает в
Easy ITSM Microsoft App ID, арендатора Entra (ID или домен) и секрет клиента. При сохранении сервер получает
токен приложения в Microsoft Entra (`{tenant}/oauth2/v2.0/token`, client credentials); неверный секрет не
сохраняется. После сохранения экран показывает Messaging endpoint для Azure:

```
POST /api/v1/integrations/teams/{id}/messages     без входа; Authorization: Bearer <JWT Bot Framework>
```

- JWT проверяется: подпись ключом из `login.botframework.com` (ключи кешируются), издатель
  `https://api.botframework.com`, аудитория — App ID бота, срок. Неизвестный или выключенный бот — тот же 401,
  чтобы не раскрывать, какие боты есть. Тело — до 256 КБ, частота ограничена, как у мониторинга.
- `serviceUrl` запроса должен совпадать с утверждением в JWT и вести на узел Bot Framework
  (`smba.trafficmanager.net`, `*.botframework.com`, `*.botframework.us`, `*.teams.microsoft.com`) по https.
  Он запоминается за разговором, туда уходят ответы и уведомления с токеном Entra (кешируется до истечения).
- Принимаются только личные разговоры. Упоминания и HTML из текста убираются.
- Привязка — кодом из профиля в сообщении боту; команды `help` и `stop` без косой черты.
- Комментарий — ответ с цитатой сообщения бота (Teams передаёт id цитаты) или сообщение, которое начинается
  с номера заявки: `INC-000123: текст`. Номер в начале работает во всех мессенджерах.

Изменения ботов пишутся в журнал версий (`integrations.messenger`, без токенов и секретов) и в аудит раздела
`integrations`; приём сообщений Teams в аудит настроек не попадает.
