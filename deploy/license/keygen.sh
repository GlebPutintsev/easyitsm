#!/usr/bin/env bash
# Создаёт ключ вендора Ed25519 для подписи лицензий (ADM-07). Выполняется один раз на смену ключа,
# на изолированной машине вендора.
#
#   ./keygen.sh vendor-2026
#
# Результат:
#   vendor-2026.key — закрытый ключ. Хранить офлайн (сейф, HSM, OpenBao). В репозиторий не попадает.
#   vendor-2026.pub — открытый ключ. Кладётся в backend/src/main/resources/licensing/keys/vendor-2026.pub,
#                     и новая версия продукта начинает принимать лицензии этого ключа.
set -euo pipefail

kid="${1:?Укажите идентификатор ключа, например vendor-2026}"
[[ "$kid" =~ ^[a-z0-9][a-z0-9-]{1,62}$ ]] || { echo "Идентификатор: строчные латинские буквы, цифры и дефис" >&2; exit 1; }
[[ -e "$kid.key" ]] && { echo "$kid.key уже существует" >&2; exit 1; }

umask 077
openssl genpkey -algorithm ed25519 -out "$kid.key"
openssl pkey -in "$kid.key" -pubout -out "$kid.pub"
echo "Закрытый ключ: $kid.key (уберите в офлайн-хранилище)"
echo "Открытый ключ: $kid.pub -> backend/src/main/resources/licensing/keys/$kid.pub"
echo "Отпечаток SHA-256: $(openssl pkey -pubin -in "$kid.pub" -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1)"
