# Офлайн-комплект Easy ITSM

Комплект устанавливает Easy ITSM в Kubernetes без доступа к интернету (SEC-39, раздел 12 ТЗ).

## Состав

| Путь | Что это |
| --- | --- |
| `images/core`, `images/web`, `images/postgres` | Образы вместе с подписями и аттестациями SBOM |
| `chart/easyitsm-<версия>.tgz` и `.sig` | Helm-чарт с закреплёнными digest образов и его подпись |
| `chart/values-ha.yaml` | Значения для промышленной HA-конфигурации |
| `sbom/*.cdx.json` | SBOM CycloneDX каждого образа |
| `tools/helm`, `tools/cosign` | Утилиты для linux-amd64 |
| `cosign.pub` | Открытый ключ вендора |
| `SHA256SUMS` и `.sig` | Контрольные суммы всех файлов и их подпись |
| `MANIFEST.json` | Версия, ревизия и digest образов |

## Установка

Нужны `kubectl` с правами администратора пространства имён и реестр образов в контуре, в который выполнен `docker login` (или настроен `~/.docker/config.json`).

```bash
tar -xf easyitsm-0.1.0-offline.tar && cd easyitsm-0.1.0
export EASYITSM_VENDOR_KEY_SHA256=<отпечаток из договора>
./install.sh verify                                     # только проверка подписей
./install.sh install -r registry.corp.local/easyitsm -n easyitsm -f my-values.yaml
./install.sh install -r registry.corp.local/easyitsm -n easyitsm -f my-values.yaml --ha
```

Установщик по шагам раздела 12.3 ТЗ:

1. Проверяет отпечаток ключа вендора, подпись `SHA256SUMS`, контрольные суммы всех файлов и подпись чарта.
2. Проверяет версию Kubernetes (1.30+), наличие StorageClass и IngressClass.
3. Загружает образы в реестр заказчика и проверяет их подписи уже в реестре, по digest.
4. Помечает пространство имён профилем Pod Security `restricted` и выполняет `helm upgrade --install --wait`.
5. Ждёт готовности ядра.

Обновление: `./install.sh upgrade ... --backup-done`. Без флага установщик откажется работать, пока не сделана резервная копия БД и S3.

## Значения, которые обычно задаёт заказчик

```yaml
ingress:
  className: nginx
  host: itsm.corp.local
  tls: { secretName: itsm-tls }
tenancy:
  defaultTenantId: 01930000-0000-7000-8000-000000000001
license:
  existingSecret: easyitsm-license      # kubectl create secret generic easyitsm-license --from-file=license.json
networkPolicy:
  ingressController:
    namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: ingress-nginx } }
  coreEgress:                            # почта, IdP, S3, SIEM
    - to: [{ ipBlock: { cidr: 10.10.0.25/32 } }]
      ports: [{ port: 587, protocol: TCP }]
```

Для HA с внешним PostgreSQL: `database.host`, `database.existingSecret` (ключи `app-username`, `app-password`, `owner-username`, `owner-password`) и `networkPolicy.databaseCidrs`.

## Ручная проверка подписей

```bash
cosign verify-blob --key cosign.pub --insecure-ignore-tlog --signature SHA256SUMS.sig SHA256SUMS
sha256sum -c SHA256SUMS
cosign verify --key cosign.pub --insecure-ignore-tlog registry.corp.local/easyitsm/easyitsm-core@sha256:...
```

`--insecure-ignore-tlog` отключает только обращение к публичному журналу Rekor, которого в закрытом контуре нет. Подпись проверяется полностью по ключу вендора.

## Для вендора: ключ подписи

Один раз, на защищённой машине:

```bash
cosign generate-key-pair            # cosign.key, cosign.pub
```

`cosign.key` и пароль сохраняются в секретах репозитория `COSIGN_PRIVATE_KEY` и `COSIGN_PASSWORD`, копия ключа — в офлайн-хранилище. Отпечаток `cosign.pub` публикуется в договоре и на портале поддержки. Выпуск: `git tag v0.1.0 && git push --tags`.
