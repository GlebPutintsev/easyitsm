#!/usr/bin/env bash
# Выпуск файла лицензии (ADM-07). Работает офлайн, нужен только OpenSSL 3.
#
#   ./sign-license.sh -k vendor-2026.key -i EI-2026-0001 -l "ООО Ромашка" -a 50 -u 2027-12-31 > license.json
#
# Параметры:
#   -k  закрытый ключ вендора (keygen.sh); идентификатор ключа берётся из имени файла
#   -i  номер лицензии
#   -l  владелец лицензии
#   -a  лимит агентов
#   -u  последний день действия, ГГГГ-ММ-ДД (включительно, UTC)
#   -f  начало действия, ГГГГ-ММ-ДД (по умолчанию сегодня)
#   -F  дополнительное право; можно указать несколько раз
set -euo pipefail

key="" id="" licensee="" agents="" until="" from="$(date -u +%Y-%m-%d)" features=()
while getopts "k:i:l:a:u:f:F:" opt; do
  case "$opt" in
    k) key="$OPTARG" ;; i) id="$OPTARG" ;; l) licensee="$OPTARG" ;; a) agents="$OPTARG" ;;
    u) until="$OPTARG" ;; f) from="$OPTARG" ;; F) features+=("$OPTARG") ;;
    *) exit 2 ;;
  esac
done
[[ -n "$key" && -n "$id" && -n "$licensee" && -n "$agents" && -n "$until" ]] || { sed -n '2,15p' "$0" >&2; exit 2; }
[[ "$agents" =~ ^[1-9][0-9]*$ ]] || { echo "-a: целое число больше нуля" >&2; exit 2; }
for d in "$from" "$until"; do [[ "$d" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "Дата в формате ГГГГ-ММ-ДД: $d" >&2; exit 2; }; done

kid="$(basename "$key" .key)"
json_escape() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; printf '"%s"' "$s"; }
feature_list=""
for f in ${features[@]+"${features[@]}"}; do feature_list+="${feature_list:+,}$(json_escape "$f")"; done

# Окончание — начало следующего дня после -u: лицензия действует весь последний день.
next_day() { python3 -c 'import sys,datetime;print((datetime.date.fromisoformat(sys.argv[1])+datetime.timedelta(days=1)).isoformat())' "$1"; }

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
printf '{"licenseId":%s,"licensee":%s,"issuedAt":"%s","notBefore":"%sT00:00:00Z","notAfter":"%sT00:00:00Z","maxAgents":%s,"features":[%s]}' \
  "$(json_escape "$id")" "$(json_escape "$licensee")" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$from" "$(next_day "$until")" \
  "$agents" "$feature_list" > "$work/payload.json"
openssl pkeyutl -sign -inkey "$key" -rawin -in "$work/payload.json" -out "$work/payload.sig"

printf '{"format":"easyitsm-license/1","kid":"%s","payload":"%s","signature":"%s"}\n' \
  "$kid" "$(openssl base64 -A -in "$work/payload.json")" "$(openssl base64 -A -in "$work/payload.sig")"
