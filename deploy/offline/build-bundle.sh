#!/usr/bin/env bash
# Сборка офлайн-комплекта (раздел 12.2 ТЗ). Запускается в release.yml после публикации и подписи образов.
#
#   CORE_DIGEST=sha256:... WEB_DIGEST=sha256:... POSTGRES_DIGEST=sha256:... \
#   COSIGN_PRIVATE_KEY=... COSIGN_PASSWORD=... ./build-bundle.sh 0.1.0 dist
#
# Результат в dist/:
#   easyitsm-<версия>-offline.tar (+ .sha256, .sig) — образы с подписями, чарт, SBOM, установщик, утилиты
#   easyitsm-<версия>.tgz (+ .sig)                   — Helm-чарт с закреплёнными digest образов
# AGENT_DIR — архивы агента обнаружения с подписями (discovery-agent/build.sh), кладутся в agent/ комплекта.
#   cosign.pub                                       — открытый ключ для проверки
set -euo pipefail

version="${1:?версия}"
out="$(mkdir -p "${2:?каталог результата}" && cd "$2" && pwd)"
registry="${REGISTRY:-ghcr.io/glebputintsev}"
helm_version="${HELM_VERSION:-v3.22.1}"
cosign_version="${COSIGN_VERSION:-v2.6.5}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${CORE_DIGEST:?}" "${WEB_DIGEST:?}" "${POSTGRES_DIGEST:?}" "${COSIGN_PRIVATE_KEY:?}"

name="easyitsm-$version"
stage="$(mktemp -d)/$name"
mkdir -p "$stage"/{chart,images,sbom,tools}
sign() { cosign sign-blob --yes --key env://COSIGN_PRIVATE_KEY --tlog-upload=false --output-signature "$1.sig" "$1" >/dev/null; }

echo "== Чарт с закреплёнными образами"
chart="$(mktemp -d)/easyitsm"
cp -r "$root/deploy/helm/easyitsm" "$chart"
yq -i "
  .image.core.repository = \"$registry/easyitsm-core\" | .image.core.digest = \"$CORE_DIGEST\" |
  .image.web.repository = \"$registry/easyitsm-web\" | .image.web.digest = \"$WEB_DIGEST\" |
  .postgresql.image.repository = \"$registry/easyitsm-postgres\" | .postgresql.image.digest = \"$POSTGRES_DIGEST\"
" "$chart/values.yaml"
helm package "$chart" --version "$version" --app-version "$version" --destination "$out" >/dev/null
sign "$out/$name.tgz"
cp "$out/$name.tgz" "$out/$name.tgz.sig" "$stage/chart/"
cp "$root/deploy/helm/easyitsm/values-ha.yaml" "$stage/chart/"

echo "== Образы с подписями и аттестациями SBOM"
for c in core web postgres; do
  var="${c^^}_DIGEST"
  cosign save --dir "$stage/images/$c" "$registry/easyitsm-$c@${!var}"
done

echo "== SBOM"
cp "$root"/sbom/*.cdx.json "$stage/sbom/" 2>/dev/null || cp sbom/*.cdx.json "$stage/sbom/"

if [ -n "${AGENT_DIR:-}" ]; then
  echo "== Агент обнаружения"
  mkdir -p "$stage/agent"
  cp "$AGENT_DIR"/easyitsm-discovery-agent-* "$stage/agent/"
  cp "$AGENT_DIR"/sbom-discovery-agent.cdx.json "$stage/sbom/" 2>/dev/null || true
fi

echo "== Утилиты для установки без интернета (linux-amd64)"
tmp="$(mktemp -d)"
curl -fsSL -o "$tmp/helm.tgz" "https://get.helm.sh/helm-$helm_version-linux-amd64.tar.gz"
curl -fsSL -o "$tmp/helm.sha256" "https://get.helm.sh/helm-$helm_version-linux-amd64.tar.gz.sha256sum"
(cd "$tmp" && echo "$(cut -d' ' -f1 helm.sha256)  helm.tgz" | sha256sum -c -)
tar -xzf "$tmp/helm.tgz" -C "$tmp" && cp "$tmp/linux-amd64/helm" "$stage/tools/helm"
curl -fsSL -o "$stage/tools/cosign" "https://github.com/sigstore/cosign/releases/download/$cosign_version/cosign-linux-amd64"
curl -fsSL -o "$tmp/cosign_checksums.txt" "https://github.com/sigstore/cosign/releases/download/$cosign_version/cosign_checksums.txt"
(cd "$stage/tools" && grep ' cosign-linux-amd64$' "$tmp/cosign_checksums.txt" | sed 's/cosign-linux-amd64/cosign/' | sha256sum -c -)
chmod +x "$stage/tools/"*

echo "== Установщик и манифест"
cp "$root/deploy/offline/install.sh" "$root/deploy/offline/README.md" "$stage/"
cosign public-key --key env://COSIGN_PRIVATE_KEY > "$stage/cosign.pub"
cp "$stage/cosign.pub" "$out/cosign.pub"
cat > "$stage/MANIFEST.json" <<JSON
{
  "product": "easyitsm",
  "version": "$version",
  "revision": "${GITHUB_SHA:-unknown}",
  "built_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "images": {
    "core": "$registry/easyitsm-core@$CORE_DIGEST",
    "web": "$registry/easyitsm-web@$WEB_DIGEST",
    "postgres": "$registry/easyitsm-postgres@$POSTGRES_DIGEST"
  },
  "chart": "chart/$name.tgz"
}
JSON

echo "== Контрольные суммы и подпись"
(cd "$stage" && find . -type f ! -name SHA256SUMS ! -name SHA256SUMS.sig -printf '%P\n' | LC_ALL=C sort | xargs -d '\n' sha256sum > SHA256SUMS)
sign "$stage/SHA256SUMS"

tar -C "$(dirname "$stage")" -cf "$out/$name-offline.tar" "$name"
(cd "$out" && sha256sum "$name-offline.tar" > "$name-offline.tar.sha256")
sign "$out/$name-offline.tar"
echo "Готово: $out/$name-offline.tar"
