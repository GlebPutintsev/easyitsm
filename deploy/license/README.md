# Лицензии Easy ITSM

Лицензия — подписанный файл, который проверяется в продукте без обращения к вендору (ADM-07).

## Формат `easyitsm-license/1`

```json
{"format":"easyitsm-license/1","kid":"vendor-2026","payload":"<base64>","signature":"<base64>"}
```

`payload` после декодирования:

```json
{"licenseId":"EI-2026-0001","licensee":"ООО Ромашка","issuedAt":"2026-11-05T10:00:00Z",
 "notBefore":"2026-11-05T00:00:00Z","notAfter":"2028-01-01T00:00:00Z","maxAgents":50,"features":[]}
```

`signature` — подпись Ed25519 над байтами `payload`. Открытый ключ `kid` должен лежать в `backend/src/main/resources/licensing/keys/<kid>.pub`.

## Ключ вендора

```bash
./keygen.sh vendor-2026
```

`vendor-2026.key` — в офлайн-хранилище, в репозиторий не попадает. `vendor-2026.pub` — в `backend/src/main/resources/licensing/keys/`. Пока там нет ни одного ключа, продукт принимает только пробный режим. При смене ключа старый открытый ключ остаётся в каталоге, чтобы выданные лицензии продолжали действовать.

## Выпуск лицензии

```bash
./sign-license.sh -k vendor-2026.key -i EI-2026-0001 -l "ООО Ромашка" -a 50 -u 2027-12-31 > license.json
```

Нужен только OpenSSL 3 и python3. Заказчик загружает файл в «Управление → Лицензия» (`PUT /api/v1/admin/license`) или передаёт секретом при установке (`license.existingSecret`).

## Поведение продукта

| Состояние | Когда | Лимит агентов |
| --- | --- | --- |
| `TRIAL` | Лицензия не установлена | 3 |
| `VALID` | Подпись верна, срок идёт | Из лицензии |
| `NOT_YET_VALID` | Срок ещё не начался | 3 |
| `EXPIRED` | Срок закончился | 3 |
| `INVALID` | Подпись не сходится, ключ неизвестен, файл повреждён | 3 |

Заявки, SLA и портал работают в любом состоянии. Лимит проверяется при выдаче роли агента (`LicenseQueries.requireAgentSeats`). За 30 дней до окончания `expiresSoon = true`, метрика `easyitsm_license_days_left` позволяет настроить оповещение.
