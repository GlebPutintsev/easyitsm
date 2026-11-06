#!/usr/bin/env bash
# Сборка агента для выпуска: один статический файл на платформу, архивы и контрольные суммы.
#   ./build.sh 1.0.0 dist
set -euo pipefail

version="${1:?версия}"
out="$(mkdir -p "${2:?каталог результата}" && cd "$2" && pwd)"
root="$(cd "$(dirname "$0")" && pwd)"
targets="${TARGETS:-linux/amd64 linux/arm64 windows/amd64}"

cd "$root"
for t in $targets; do
  os="${t%/*}" arch="${t#*/}"
  name="easyitsm-discovery-agent-$version-$os-$arch"
  stage="$(mktemp -d)/$name"
  mkdir -p "$stage"
  bin="discovery-agent"
  [ "$os" = windows ] && bin="discovery-agent.exe"
  CGO_ENABLED=0 GOOS="$os" GOARCH="$arch" go build -trimpath -ldflags="-s -w -X main.version=$version" \
    -o "$stage/$bin" .
  cp README.md deploy/discovery.example.json "$stage/"
  if [ "$os" = windows ]; then
    (cd "$(dirname "$stage")" && zip -qr "$out/$name.zip" "$name")
  else
    cp deploy/easyitsm-discovery.service "$stage/"
    tar -C "$(dirname "$stage")" -czf "$out/$name.tar.gz" "$name"
  fi
  echo "$name"
done
(cd "$out" && sha256sum easyitsm-discovery-agent-"$version"-* > "easyitsm-discovery-agent-$version.sha256")
