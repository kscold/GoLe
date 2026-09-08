#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
work="$(mktemp -d)"
trap 'find "$work" -depth -delete' EXIT
git init -q -b main "$work/origin"
git -C "$work/origin" -c user.name=Test -c user.email=test@example.invalid commit -q --allow-empty -m previous
previous="$(git -C "$work/origin" rev-parse HEAD)"
git -C "$work/origin" -c user.name=Test -c user.email=test@example.invalid commit -q --allow-empty -m candidate
git clone -q --depth=1 "file://$work/origin" "$work/runner"
cd "$work/runner"
test "$(git rev-parse --is-shallow-repository)" = true
if git merge-base --is-ancestor "$previous" HEAD 2>/dev/null; then
  echo 'fixture must not contain the previous release ancestry' >&2
  exit 1
fi
block="$(sed -n '/^          if \[ "$(git rev-parse --is-shallow-repository)" = true \]; then$/,/^          git fetch --prune origin main$/p' "$ROOT/.github/workflows/cd.yml" | sed 's/^          //')"
test -n "$block"
eval "$block"
test "$(git rev-parse --is-shallow-repository)" = false
git merge-base --is-ancestor "$previous" HEAD
eval "$block"
echo 'CD restores shallow ancestry and accepts subsequent full-history retries.'
