# Easy ITSM

Коробочная ITSM-платформа: инциденты, запросы и каталог услуг, знания, SLA, портал, изменения, проблемы, CMDB, активы и ИИ-помощник. Один код для контура заказчика (в том числе без интернета) и облака.

- Требования: [ТЗ.md](ТЗ.md)
- План, модули и порядок работ: [PLAN.md](PLAN.md)
- Архитектурные решения: [docs/adr](docs/adr)
- Как добавить модуль: [CONTRIBUTING.md](CONTRIBUTING.md)

## Структура

| Папка | Что внутри |
| --- | --- |
| `backend/` | Модульный монолит: Java 21, Spring Boot 4.1, Spring Modulith, PostgreSQL |
| `frontend/` | Портал, рабочее место агента и управление: React 19, TypeScript, Vite, Mantine, TanStack Query |
| `deploy/compose/` | Окружение разработки |
| `deploy/docker/`, `deploy/helm/`, `deploy/offline/` | Образы без оболочки, Helm-чарт, офлайн-комплект и установщик ([как установить](deploy/offline/README.md)) |
| `deploy/license/` | Выпуск подписанных лицензий ([формат и ключи](deploy/license/README.md)) |
| `docs/` | ADR, спецификация API |

## Запуск для разработки

Нужны JDK 21, Maven 3.9, Node 22 и Docker.

```bash
# 1. PostgreSQL, Valkey, MinIO, Mailpit (Keycloak: --profile sso)
docker compose -f deploy/compose/compose.yaml up -d

# 2. Бэкенд на :8080, профиль dev включает демо-арендатора и вход по заголовкам X-Dev-*
cd backend && SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run

# 3. Фронтенд на :5173, запросы /api проксируются на бэкенд
cd frontend && npm install && npm run dev
```

Тесты:

```bash
cd backend && mvn verify      # границы модулей, изоляция арендаторов в БД, соглашения API
cd frontend && npm run lint && npm run typecheck && npm test
```

Бэкенд-тесты работают с настоящим PostgreSQL (база `easyitsm_test` из `deploy/compose/postgres/init.sql`), потому что RLS нельзя проверить на встроенной БД. Другой адрес задаётся переменной `EASYITSM_TEST_DB_URL`.
