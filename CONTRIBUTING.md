# Как работать с репозиторием

Модули разрабатываются параллельно в отдельных ветках. Эти правила нужны, чтобы ветки сливались без конфликтов и без нарушения архитектуры.

## Ветки и PR

- Ветка на задачу: `<модуль>/<кратко>`, например `tickets/core-model`.
- PR в `main`, CI должен быть зелёным. Изменения в `platform`, корневых конфигах и `frontend/src/app` — отдельным небольшим PR с пояснением, зачем это нужно всем.
- Миграции не редактируются после слияния: только новая миграция. Версия уникальна во всём ядре, не только в папке модуля: `V<год>_<мм>_<дд>_<ччмм>__<модуль>_<что>.sql`, своя минута. Совпадения ловит проверка `migrations` в CI (`.github/scripts/check-migration-versions.sh`).

## Новый бэкенд-модуль

Образец — модуль `announcements`.

```
backend/src/main/java/ru/easyitsm/<module>/
├── package-info.java      @ApplicationModule(displayName = "...")
├── api/                   публичные интерфейсы и DTO (@NamedInterface("api"))
├── events/                доменные события (@NamedInterface("events"))
└── internal/              сущности, репозитории, сервисы, контроллеры
backend/src/main/resources/db/migration/<module>/
└── VYYYY_MM_DD_HHMM__<module>_<описание>.sql
```

Обязательно:

1. Сущность наследует `TenantEntity`: UUIDv7, `tenant_id`, служебные поля и `version` заполняются сами.
2. В миграции у таблицы есть `tenant_id`, служебные поля, `ENABLE ROW LEVEL SECURITY` и политика `tenant_isolation` как в `announcements`.
3. Другой модуль вызывается только через его `api`, реакция на чужие изменения — через `@ApplicationModuleListener` на его `events`.
4. Контроллер под `/api/v1/...`: ошибки через `ApiException`, списки через `PageParams` и `CursorPage`, изменение через `ETags.requireMatch`, права через `@PreAuthorize` с ролями из раздела 3 ТЗ (`ROLE_AGT`, `ROLE_ADM` и т. д.).
5. Чужая или отсутствующая запись — 404. Тесты прав и изоляции обязательны для каждого эндпоинта (NFR-16).
6. Арендатор в фоновых задачах задаётся явно через `TenantContext.runAs`. Задачи помечаются `@ConditionalOnProperty(name = "easyitsm.worker.enabled", havingValue = "true", matchIfMissing = true)`.

## Аудит и безопасность

Модуль `audit` пишет журнал сам: вход, роли и группы — по событиям identity; ответы 403 и 404 на запрос к записи — как «отказ в доступе»; действия с `@RequiresRecentAuth` и изменение настроек — по маршруту. Модулю нужно немногое:

- Новый экран настроек — добавить префикс маршрута в `audit/internal/AuditRoutes`.
- Удаление, назначение ролей, членство в группах и выгрузка — `@RequiresRecentAuth` (SEC-08). Тест `OwaspApiTest` найдёт DELETE и `/export` без неё; осознанное исключение вносится в его список с причиной.
- Чтение чувствительных данных и массовая выгрузка — запись через `ru.easyitsm.audit.api.AuditLog` (`exported(...)`), права и причина — `BulkExport` (SEC-14).
- Секреты и персональные данные не передаются ни в журнал, ни в логи. Страховка — `Masking` (журнал, SIEM, сообщения логов через `logback-spring.xml`).
- HTML извне (письма, интеграции) — только через `ru.easyitsm.platform.text.SafeHtml`: `toText` для заявок, `sanitize` для показа (SEC-24).
- Каждый эндпоинт проверяет роль (`@PreAuthorize`): `OwaspApiTest` обходит все эндпоинты приложения без входа и с инъекциями в поиске.

## Новый раздел фронтенда

- Вид экранов — по `DESIGN.md` и макетам по ссылке из него. Цвета только через токены `var(--ei-…)`, статус, приоритет и SLA — через `StatusBadge`, `PriorityBadge`, `SlaTimer` из `src/shared/ui`.
- Код раздела в `frontend/src/features/<модуль>/`, маршруты добавляются в `src/app/router.tsx`, пункт меню — в `src/app/layout/spaces.ts`.
- Все тексты в `src/shared/i18n/locales/ru.json` и `en.json`, линтер предупреждает о русском тексте в JSX.
- Запросы через `src/shared/api/client.ts` и TanStack Query. Каждый список показывает состояния через `ListState` (UI-02).
- Создание отправляет `Idempotency-Key`, изменение — `If-Match` с ETag записи.

## Вход в режиме разработки

Интерфейс входит как в поставке: экран входа, кука сессии `__Host-eitsm_session` (по http в dev просто `eitsm_session`) и CSRF-токен из куки `XSRF-TOKEN`, который `client.ts` отправляет в заголовке `X-XSRF-TOKEN`. Демо-пользователи арендатора `demo`, пароль у всех `Demo-ITSM-2026!`:

- `admin` — ADM и SEC, участник группы Service Desk;
- `agent` — руководитель группы Service Desk (LEAD, группа даёт AGT);
- `user` — заявитель (REQ).

В dev MFA не обязательна, её можно включить в «Профиль → Безопасность». Вход через Keycloak: `docker compose -f deploy/compose/compose.yaml --profile sso up -d keycloak` и профили `dev,sso`, пользователь `sso.user` с тем же паролем.

Тесты бэкенда и скрипты могут по-прежнему передавать заголовки `X-Dev-User`, `X-Dev-Tenant` и `X-Dev-Roles` (через запятую: `AGT,ADM`) — только в профилях `dev` и `test`, в других приложение не стартует. Такие запросы не требуют CSRF. Опасные действия с `@RequiresRecentAuth` с этими заголовками проходят без повторного входа. Контракт модуля для других модулей: `ru.easyitsm.identity.api` (`UserDirectory`, `AccessDecisions`, `Role`).
