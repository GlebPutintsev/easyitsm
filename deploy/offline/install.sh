#!/usr/bin/env bash
# Установщик Easy ITSM из офлайн-комплекта (SEC-39, раздел 12.3 ТЗ). Интернет не нужен.
#
#   ./install.sh verify
#   ./install.sh install -r registry.corp.local/easyitsm -n easyitsm [-f my-values.yaml] [--ha]
#   ./install.sh upgrade -r registry.corp.local/easyitsm -n easyitsm [-f my-values.yaml] --backup-done
#
# Нужны: kubectl с доступом к кластеру, реестр образов заказчика с правом записи (docker login заранее).
# helm и cosign берутся из tools/ комплекта.
set -euo pipefail

cd "$(dirname "$0")"
here="$(pwd)"
PATH="$here/tools:$PATH"
release="easyitsm"

say()  { printf '\n== %s\n' "$*"; }
fail() { printf 'Ошибка: %s\n' "$*" >&2; exit 1; }
json() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" MANIFEST.json | head -1; }

# cosign подписывает ключом вендора без журнала прозрачности: проверка не обращается в интернет.
# Флаг --insecure-ignore-tlog отключает только онлайн-журнал; подпись и ключ проверяются полностью.
COSIGN_VERIFY=(--key "$here/cosign.pub" --insecure-ignore-tlog=true)

verify_bundle() {
  say "Ключ вендора"
  fp="$(openssl pkey -pubin -in cosign.pub -outform DER 2>/dev/null | sha256sum | cut -d' ' -f1)"
  echo "Отпечаток SHA-256: $fp"
  if [[ -n "${EASYITSM_VENDOR_KEY_SHA256:-}" ]]; then
    [[ "$fp" == "$EASYITSM_VENDOR_KEY_SHA256" ]] || fail "отпечаток ключа не совпадает с EASYITSM_VENDOR_KEY_SHA256"
    echo "Совпадает с ожидаемым."
  else
    echo "Сверьте отпечаток с указанным в договоре или на портале поддержки,"
    echo "или задайте EASYITSM_VENDOR_KEY_SHA256 для автоматической проверки."
  fi

  say "Подпись списка файлов"
  cosign verify-blob "${COSIGN_VERIFY[@]}" --signature SHA256SUMS.sig SHA256SUMS >/dev/null || fail "подпись SHA256SUMS неверна"
  echo "Подпись верна."

  say "Контрольные суммы"
  sha256sum --quiet -c SHA256SUMS || fail "файлы комплекта изменены"
  echo "Все файлы целы."

  say "Подпись чарта"
  chart="$(json chart)"
  cosign verify-blob "${COSIGN_VERIFY[@]}" --signature "$chart.sig" "$chart" >/dev/null || fail "подпись чарта неверна"
  echo "Подпись верна."
}

preflight() {
  say "Предварительная проверка"
  command -v kubectl >/dev/null || fail "нет kubectl"
  kubectl version >/dev/null 2>&1 || fail "кластер недоступен: проверьте kubeconfig"
  minor="$(kubectl version -o json | sed -n 's/.*"minor": *"\([0-9]*\).*/\1/p' | tail -1)"
  [[ "${minor:-0}" -ge 30 ]] || fail "нужен Kubernetes 1.30 или новее"
  echo "Kubernetes 1.$minor"
  kubectl get storageclass -o name | grep -q . || echo "Внимание: в кластере нет StorageClass, встроенному PostgreSQL негде хранить данные"
  kubectl get ingressclass -o name | grep -q . || echo "Внимание: нет IngressClass, веб-интерфейс не будет доступен снаружи"
}

push_images() {
  say "Загрузка и проверка образов в $registry"
  for c in core web postgres; do
    ref="$(json "$c")"
    digest="${ref#*@}"
    cosign load --dir "images/$c" "$registry/easyitsm-$c" >/dev/null
    cosign verify "${COSIGN_VERIFY[@]}" "$registry/easyitsm-$c@$digest" >/dev/null \
      || fail "подпись образа $c в реестре не проходит проверку"
    echo "$c: $digest, подпись верна"
  done
}

deploy() {
  local mode="$1"
  kubectl get ns "$namespace" >/dev/null 2>&1 || kubectl create ns "$namespace"
  # SEC-34: пространство имён принимает только поды профиля restricted.
  kubectl label ns "$namespace" --overwrite \
    pod-security.kubernetes.io/enforce=restricted pod-security.kubernetes.io/audit=restricted \
    pod-security.kubernetes.io/warn=restricted >/dev/null

  say "Helm $mode"
  args=(upgrade --install "$release" "$(json chart)" -n "$namespace" --wait --timeout 20m
        --set "global.imageRegistry=$registry")
  [[ "$ha" == true ]] && args+=(-f chart/values-ha.yaml)
  for f in ${values[@]+"${values[@]}"}; do args+=(-f "$f"); done
  # Каталог образов в реестре заказчика: easyitsm-*, без пути вендора.
  args+=(--set "image.core.repository=easyitsm-core" --set "image.web.repository=easyitsm-web"
         --set "postgresql.image.repository=easyitsm-postgres")
  helm "${args[@]}"

  say "Проверка после установки"
  kubectl -n "$namespace" rollout status "deploy/$release-api" --timeout 10m
  kubectl -n "$namespace" get pods -l app.kubernetes.io/instance="$release"
  echo "Готово: Easy ITSM $(json version)."
}

cmd="${1:-}"; shift || true
registry="" namespace="easyitsm" ha=false backup=false values=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -r) registry="$2"; shift 2 ;;
    -n) namespace="$2"; shift 2 ;;
    -f) values+=("$2"); shift 2 ;;
    --ha) ha=true; shift ;;
    --backup-done) backup=true; shift ;;
    *) fail "неизвестный параметр $1" ;;
  esac
done

case "$cmd" in
  verify)
    verify_bundle ;;
  install|upgrade)
    [[ -n "$registry" ]] || fail "укажите реестр образов: -r registry.corp.local/easyitsm"
    if [[ "$cmd" == upgrade && "$backup" != true ]]; then
      fail "перед обновлением сделайте резервную копию БД и S3 (раздел 12.3 ТЗ) и запустите с --backup-done"
    fi
    verify_bundle
    preflight
    push_images
    deploy "$cmd" ;;
  *)
    sed -n '2,9p' "$0"; exit 2 ;;
esac
