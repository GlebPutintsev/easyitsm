# Поиск и вложения (модули search и attachments)

Требования: TCK-04, TCK-15, SEC-25…27, NFR-04, AT-08 (раздел 10 PLAN.md).

## Глобальный поиск — `GET /api/v1/search`

| Параметр | Значение |
| --- | --- |
| `q` | 2…200 символов. Номер заявки (`INC-000123`) находит её сразу |
| `types` | через запятую: `ticket`, `article`, `service`; по умолчанию все |
| `ticketType` | `incident`, `request`, … через запятую |
| `state` | `open` или `closed` |
| `updatedFrom` | ISO-8601 |
| `limit` | на группу, до 20 |

Ответ:

```json
{ "engine": "opensearch", "degraded": false,
  "groups": [ { "kind": "ticket", "total": 3,
    "items": [ { "kind": "ticket", "id": "…", "ref": "INC-000042",
      "title": [ { "text": "Не работает ", "hl": false }, { "text": "VPN", "hl": true } ],
      "snippet": [ … ], "ticketType": "incident", "status": "in_progress", "statusCategory": "in_progress",
      "priority": "P2", "updatedAt": "…" } ] } ] }
```

Подсветка приходит кусками `{text, hl}`, а не HTML: интерфейс рисует `<mark>` сам, внедрить разметку
через текст заявки нельзя. Ошибки: `query_too_short`, `query_too_long`, `invalid_state`, `invalid_filter`.

**Права.** Заявка находится тогда же, когда открывается: группы, конфиденциальность, участники, организация,
внутренние заметки только тем, кто их видит (`TicketReadScope`). Статья — по аудитории и группе
(`ArticleReadScope`), черновики и архив видят только редакторы знаний. Фильтр прав строится в запросе
OpenSearch, а затем страница выдачи перепроверяется владельцем данных (`visibleAmong`, `readableAmong`):
если индекс отстал от смены группы, лишнее отбрасывается. Услуги ищутся через `CatalogQueries.search`
с правами каталога.

**Движок.** OpenSearch (`easyitsm.search.opensearch.enabled=true`): анализатор `eitsm_text`
(ё→е, стоп-слова и стемминг русский и английский), поиск по префиксу последнего слова и с опечатками.
Без OpenSearch, пока строится индекс арендатора или когда узлы недоступны, работает запасной поиск PostgreSQL
(`to_tsquery('russian')`) с теми же правами; ответ тогда `engine=postgres`, `degraded=true` и
в интерфейсе видна плашка (AT-08). После сбоя OpenSearch не опрашивается `retryAfter` (30 с).

**Индексация.** События заявок и знаний ставят запись в очередь `search_index_queue`; фоновый обработчик
(`worker`) раз в секунду отправляет пачки `_bulk` с `require_alias=true`. Изменение появляется в поиске
за 1–3 с (NFR-04: до 10 с). Индекс — `<prefix>-v1-000001` за псевдонимом `<prefix>-v1`, один на все
арендаторы с полем `tenantId` в каждом запросе. Ночная сверка (`reconcile`, 03:30) переотправляет всё.

| Метод | Кто | Что |
| --- | --- | --- |
| `GET /search/status` | ADM, SEC | движок, доступность, индекс готов, очередь, последняя ошибка |
| `POST /search/reindex` | ADM | полная переиндексация арендатора (202); поиск работает по старому индексу |

## Вложения — `/api/v1/attachments`

Файл не проходит через ядро. Браузер получает подписанную ссылку и кладёт файл прямо в S3, ядро проверяет
его в карантине и только потом выдаёт ссылку на скачивание.

1. `POST /attachments` `{ownerType: ticket|article, ownerId, fileName, size, contentType, visibility: public|internal}` →
   `201 {attachment, upload: {url, method: PUT, headers, expiresAt}}`. Ссылка живёт 15 минут, ключ —
   `quarantine/<tenant>/<id>`.
2. Браузер выполняет `PUT upload.url` с `upload.headers`.
3. `POST /attachments/{id}/complete` → статус `scanning`. Фоновая проверка: размер совпал, расширение не из
   запрещённых, сигнатура содержимого (исполняемые файлы под чужим расширением), архивы (глубина 3,
   10 000 файлов, 1 ГБ распакованного, сжатие не больше 200:1, зашифрованные — нет), затем ClamAV (`INSTREAM`).
4. Чистый файл переносится в `files/<tenant>/<id>` → `clean`. Иначе `infected` (сигнатура в `verdict`) или
   `blocked` (правило в `verdict`, например `executable:application/x-msdownload`) и событие
   `AttachmentQuarantined` для аудита и SOC.

| Метод | Что |
| --- | --- |
| `GET /attachments?ownerType=&ownerId=` | файлы владельца, внутренние — только тем, кто видит внутренние заметки |
| `GET /attachments/{id}` | один файл |
| `GET /attachments/{id}/download` | `303` на ссылку S3 на 5 минут, `Content-Disposition: attachment`, `application/octet-stream` |
| `GET /attachments/{id}/preview` | `303`, `inline` только для png, jpeg, gif, webp; иначе `preview_unavailable` |
| `DELETE /attachments/{id}` | автор, поддержка заявки, ADM или SEC; мягкое удаление, файл стирается из S3 |
| `GET /attachments/quarantine` | SEC, ADM: карантин с причинами, курсор |
| `POST /attachments/{id}/rescan` | SEC: повторная проверка, например после обновления баз |

Права наследуются от владельца: файл заявки виден тем, кто видит заявку (`TicketQueries.permissions`),
добавлять — кто может комментировать незакрытую заявку, внутренние — поддержка; файл статьи — по правам
статьи (`ArticleQueries.permissions`), добавлять — редакторы. Чужой или невидимый владелец — `404`.

Ошибки: `file_too_large` (больше 100 МБ), `file_type_blocked`, `too_many_attachments` (200 на владельца),
`upload_rate_limited` (30 в минуту, 300 в час на пользователя), `upload_missing`, `upload_size_mismatch`,
`attachment_scanning`, `attachment_quarantined`, `invalid_file_name`, `invalid_visibility`, `forbidden`.

События (`ru.easyitsm.attachments.events`): `AttachmentAdded` (чистый файл доступен) и
`AttachmentQuarantined`.

### Java API для модулей (`ru.easyitsm.attachments.api`)

```java
AttachmentIntake.Accepted AttachmentIntake.accept(IncomingFile file)   // почта, мессенджеры
IncomingFile.ticketMail(ticketId, fileName, contentType, byte[] content, senderUserIdOrNull)
```

Файл без браузера: сразу в карантин S3 и на ту же проверку (содержимое, архивы, ClamAV), затем
`AttachmentAdded` или `AttachmentQuarantined`. Вызов в арендаторе вызывающего и без проверки прав пользователя.
Ответ — `{id, status: scanning | blocked, reason}`: файл больше 100 МБ или 201-й у записи не хранится, остаётся
отметка `blocked` (`file_too_large`, `too_many_attachments`), чтобы агент видел, что в письме был файл.
Имя очищается (путь, управляющие символы, RLO), пустое — `attachment`. Хранилище недоступно —
`AttachmentIntake.StorageUnavailable`, запись не создаётся. Сначала объект кладётся в S3, потом
пишется запись, а `accept` объявлен с `noRollbackFor = StorageUnavailable`: сбой хранилища не откатывает
транзакцию вызывающего. Почта в этом случае создаёт заявку без файлов и пишет отметку в журнал письма.

## Развёртывание

* **S3.** `easyitsm.attachments.s3.endpoint` — адрес для ядра, `public-endpoint` — для браузера, отдельный
  домен файлов (SEC-25). Ключи — файлами `EASYITSM_ATTACHMENTS_S3_ACCESS_KEY` и `…_SECRET_KEY`
  в `/run/secrets/easyitsm`. В хранилище нужен CORS для домена портала: `PUT` с заголовком `Content-Type`.
* **CSP.** Веб-клиенту нужен домен файлов в `connect-src` (загрузка) и `img-src` (предпросмотр):
  `EASYITSM_FILES_ORIGIN` у web-сервера; Helm берёт его из `attachments.s3.publicEndpoint`.
* **ClamAV.** Helm поднимает `clamd` без root и с файловой системой только для чтения; стартовые базы берутся
  из образа, обновление — `freshclam` с `database.clamav.net` или с зеркала заказчика
  (`clamav.freshclam.privateMirror`) в контуре без интернета. `attachments.antivirus.mode=none` — без антивируса,
  файлы помечаются `not_scanned`.
* **OpenSearch.** `search.opensearch.enabled`, `urls`, `username`, пароль — секретом
  `EASYITSM_SEARCH_OPENSEARCH_PASSWORD`. Индекс создаётся при старте, первая индексация арендатора — сама.

Локально: `docker compose --profile search --profile av up -d` (OpenSearch и ClamAV). Тесты на живых сервисах
включаются переменными `EASYITSM_TEST_OPENSEARCH_URL`, `EASYITSM_TEST_S3_ENDPOINT`, `EASYITSM_TEST_CLAMD_HOST`.
