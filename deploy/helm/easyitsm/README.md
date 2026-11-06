# Helm-чарт Easy ITSM

```bash
# Пилот на одном узле: ядро и обработчик в одном поде, встроенный PostgreSQL
helm install easyitsm deploy/helm/easyitsm -n easyitsm --create-namespace \
  --set ingress.host=itsm.example.local --set tenancy.defaultTenantId=<uuid>

# Промышленная HA: внешний PostgreSQL, 3 реплики api, отдельный worker
helm install easyitsm deploy/helm/easyitsm -n easyitsm -f deploy/helm/easyitsm/values-ha.yaml \
  --set database.host=pg.corp.local --set networkPolicy.databaseCidrs={10.0.5.0/24}
```

В закрытом контуре устанавливайте из подписанного офлайн-комплекта: [deploy/offline](../../offline/README.md).

## Компоненты

| Компонент | Что делает | Порты |
| --- | --- | --- |
| `api` | REST API, на пилоте ещё и фоновые задачи | 8080 HTTP, 8081 health и метрики |
| `worker` | Outbox, таймеры SLA, почта (в HA) | 8081 |
| `web` | Статика SPA | 8080 |
| `postgresql` | Встроенная БД для пилота | 5432 |

## Безопасность по умолчанию

- Профиль Pod Security `restricted`: без root, read-only корневая ФС, `seccomp RuntimeDefault`, все capabilities сброшены, токен ServiceAccount не монтируется (SEC-34).
- NetworkPolicy: запрет всего трафика пространства имён и точечные разрешения, внешние системы ядра перечисляются в `networkPolicy.coreEgress` (SEC-35).
- Пароли и лицензия монтируются файлами с правами 0440, не переменными окружения. Для внешней БД пароли передаются только через `database.existingSecret` (SEC-36).
- Порт управления 8081 не публикуется в Ingress, к нему пускается только мониторинг.

## Основные значения

| Значение | По умолчанию | Описание |
| --- | --- | --- |
| `global.imageRegistry` | `""` | Реестр-зеркало для всех образов |
| `image.core.digest`, `image.web.digest` | `""` | Digest образов, в выпущенном чарте заполнены |
| `tenancy.defaultTenantId` | `""` | Арендатор инсталляции |
| `api.replicas`, `api.workerEnabled` | `1`, `true` | Реплики и фоновая роль в том же поде |
| `worker.enabled` | `false` | Отдельный Deployment обработчика |
| `postgresql.enabled` | `true` | Встроенный PostgreSQL |
| `database.*` | | Внешний PostgreSQL |
| `license.existingSecret` | `""` | Секрет с `license.json` |
| `appSecret.existingSecret` | `""` | Секрет с `secret-key` — ключ шифрования TOTP, паролей ящиков, токенов ответа в письмах. Пусто — чарт создаст и сохранит свой |
| `publicUrl` | `""` | Адрес интерфейса для ссылок в письмах и push; пусто — из `ingress.host` |
| `mail.smtp.*` | | Сервер отправки по умолчанию для всей установки (арендатор может задать свой в «Управление → Почта») |
| `mail.inboundSmtp.*` | выключено | Приём писем по SMTP: порт, сервис (LoadBalancer), сети почтовых серверов `sourceCidrs` |
| `networkPolicy.*` | включено | Селекторы Ingress-контроллера, мониторинга, DNS, внешние адреса. Для почты и push добавьте в `networkPolicy.coreEgress` SMTP/IMAP-серверы и 443 к Microsoft Graph и push-сервисам браузеров |
