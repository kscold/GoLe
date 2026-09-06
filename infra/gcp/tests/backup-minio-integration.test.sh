#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
MINIO_CONTAINER="gole-backup-test-$(date +%s)-$$"
MC_IMAGE="minio/mc:latest@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727"
MINIO_ADMIN_TIMEOUT=30s
MINIO_ADMIN_KILL_AFTER=5s
export MINIO_ROOT_USER=gole-backup-test
export MINIO_ROOT_PASSWORD=gole-backup-test-only-password

# Exercise the production helper bodies against disposable real MinIO, not a
# Docker mock. Only replace the host's secret file with these test credentials.
timeout() {
  local args=()
  while [ "$#" -gt 0 ]; do
    if [ "$1" = --env-file ]; then
      [ "$2" = /etc/gole/infra.env ] || return 1
      args+=(--env MINIO_ROOT_USER --env MINIO_ROOT_PASSWORD)
      shift 2
    else
      args+=("$1")
      shift
    fi
  done
  set -- "${args[@]}"
  if type -P timeout >/dev/null 2>&1; then
    command timeout "$@"
  elif command -v gtimeout >/dev/null 2>&1; then
    command gtimeout "$@"
  else
    # macOS has no GNU timeout; bound the test client without changing the
    # production Linux timeout behavior. These are the helper's fixed options.
    [ "$1" = --foreground ] && [ "$2" = --kill-after=5s ] && [ "$3" = 30s ]
    shift 3
    python3 -c 'import subprocess, sys; sys.exit(subprocess.run(sys.argv[1:], timeout=35).returncode)' "$@"
  fi
}
# Load only the two reviewed functions, without running the root-only script.
eval "$(sed -n '/^run_minio_freeze()/,/^write_minio_recovery_marker()/p' \
  "$ROOT/infra/gcp/scripts/backup-data.sh" | sed '$d')"
cleanup() {
  command docker rm -f "$MINIO_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT
command docker run -d --name "$MINIO_CONTAINER" \
  --env MINIO_ROOT_USER --env MINIO_ROOT_PASSWORD \
  minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e \
  server /data >/dev/null
for attempt in $(seq 1 30); do
  if run_minio_unfreeze_and_prove >/dev/null 2>&1; then break; fi
  [ "$attempt" -lt 30 ] || { run_minio_unfreeze_and_prove; exit 1; }
  sleep 1
done
run_minio_freeze
run_minio_unfreeze_and_prove
echo 'Real MinIO backup freeze/unfreeze and S3 proof passed without a gole_data network.'
