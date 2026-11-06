#!/usr/bin/env bash
# Версия миграции Flyway должна быть уникальна во всём ядре, а не только в папке модуля:
# две V2026_11_06_1158 в разных модулях не дают Flyway стартовать.
# На pull_request GitHub проверяет ветку уже слитой с main, поэтому совпадение с чужим PR видно до слияния.
# Версии сравниваются так же, как во Flyway: «_» и «.» равнозначны, ведущие нули не важны.
set -euo pipefail

root="${1:-backend/src/main/resources/db}"

find "$root" -type f -name 'V*__*.sql' | sort | while read -r file; do
  name=$(basename "$file")
  version=${name#V}
  version=${version%%__*}
  normalized=$(echo "$version" | tr '_' '.' | awk -F. '{ for (i = 1; i <= NF; i++) { sub(/^0+/, "", $i); if ($i == "") $i = "0" } ; OFS = "."; $1 = $1; print }')
  printf '%s\t%s\n' "$normalized" "$file"
done > /tmp/migration-versions.tsv

dups=$(cut -f1 /tmp/migration-versions.tsv | sort | uniq -d)
if [ -n "$dups" ]; then
  echo "::error::Повторяются версии миграций Flyway. Возьмите для своей миграции другую минуту и сверьтесь с main."
  for v in $dups; do
    awk -F'\t' -v v="$v" '$1 == v { print "  " v ": " $2 }' /tmp/migration-versions.tsv
  done
  exit 1
fi
echo "Версии миграций уникальны: $(wc -l < /tmp/migration-versions.tsv) файлов"
