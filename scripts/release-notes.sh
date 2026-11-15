#!/usr/bin/env bash
# Печатает описание релиза: раздел версии из CHANGELOG.md, а если его нет -
# список коммитов с прошлого тега.
# Использование: scripts/release-notes.sh <версия без v> [тег]
set -euo pipefail

VERSION="${1:?нужна версия, например 1.2.0}"
TAG="${2:-v$VERSION}"
CHANGELOG="${CHANGELOG:-CHANGELOG.md}"

section=""
if [ -f "$CHANGELOG" ]; then
  section="$(awk -v v="$VERSION" '
    $0 ~ "^## \\[" v "\\]" { found = 1; next }
    found && /^## \[/ { exit }
    found { print }
  ' "$CHANGELOG")"
fi

# убираем пустые строки по краям
section="$(printf '%s\n' "$section" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"

if [ -n "${section//[[:space:]]/}" ]; then
  printf '%s\n' "$section"
else
  echo "## Что изменилось"
  echo
  previous="$(git describe --tags --abbrev=0 "${TAG}^" 2>/dev/null || true)"
  if [ -n "$previous" ]; then
    git log --pretty='- %s' "$previous..$TAG" || true
  else
    git log --pretty='- %s' "$TAG" 2>/dev/null || git log --pretty='- %s' -n 20
  fi
fi

echo
echo "---"
echo "Установка: положите \`BuildMaster-$VERSION.jar\` в папку \`plugins/\`. Нужны Paper 1.21.6+ и Vault."
echo "Контрольная сумма SHA-256 лежит рядом с файлом (\`.sha256\`)."
