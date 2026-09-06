#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TEST_ROOT="$(mktemp -d)"
cleanup() { rm -rf -- "$TEST_ROOT"; }
trap cleanup EXIT
mkdir -p "$TEST_ROOT/bin"

cat > "$TEST_ROOT/bin/gcloud" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >> "$FAKE_GCLOUD_LOG"
case "$1 $2 $3" in
  "compute resource-policies describe")
    retention=3
    if [ "${FAKE_BAD_POLICY:-0}" = 1 ]; then retention=30; fi
    cat <<JSON | jq --arg mode "${FAKE_GUEST_FLUSH:-false}" 'if $mode == "missing" then del(.snapshotSchedulePolicy.snapshotProperties.guestFlush) else .snapshotSchedulePolicy.snapshotProperties.guestFlush = ($mode | fromjson) end'
{"status":"READY","selfLink":"https://www.googleapis.com/compute/v1/projects/test-project/regions/asia-northeast3/resourcePolicies/gole-production-daily-snapshots","snapshotSchedulePolicy":{"schedule":{"dailySchedule":{"daysInCycle":1,"startTime":"20:00"}},"retentionPolicy":{"maxRetentionDays":${retention},"onSourceDiskDelete":"APPLY_RETENTION_POLICY"},"snapshotProperties":{"guestFlush":false,"storageLocations":["asia-northeast3"],"labels":{"app":"gole","environment":"production","backup":"daily","managed-by":"terraform"}}}}
JSON
    ;;
  "compute disks describe")
    if [ "${FAKE_EXTRA_DISK_POLICY:-0}" = 1 ]; then
      printf '%s\n' '{"resourcePolicies":["projects/test-project/regions/asia-northeast3/resourcePolicies/gole-production-daily-snapshots","projects/test-project/regions/asia-northeast3/resourcePolicies/unreviewed-policy"]}'
    else
      printf '%s\n' '{"resourcePolicies":["projects/test-project/regions/asia-northeast3/resourcePolicies/gole-production-daily-snapshots"]}'
    fi
    ;;
  "compute instances describe")
    deletion=true
    if [ "${FAKE_NO_DELETION_PROTECTION:-0}" = 1 ]; then deletion=false; fi
    instance_policies='[]'
    if [ "${FAKE_STALE_INSTANCE_POLICY:-0}" = 1 ]; then
      instance_policies='["projects/test-project/regions/asia-northeast3/resourcePolicies/he-testbed-office-hours"]'
    fi
    printf '{"deletionProtection":%s,"disks":[{"boot":true}],"resourcePolicies":%s}\n' \
      "$deletion" "$instance_policies"
    ;;
  "compute snapshots list")
    if [ "${FAKE_NO_SNAPSHOT:-0}" = 1 ]; then
      printf '[]\n'
    else
      cat <<'JSON'
[{"status":"READY","sourceDisk":"https://www.googleapis.com/compute/v1/projects/test-project/zones/asia-northeast3-a/disks/gole-production","sourceSnapshotSchedulePolicy":"https://www.googleapis.com/compute/v1/projects/test-project/regions/asia-northeast3/resourcePolicies/gole-production-daily-snapshots"}]
JSON
    fi
    ;;
  *) exit 77 ;;
esac
EOF
chmod 0755 "$TEST_ROOT/bin/gcloud"

cat > "$TEST_ROOT/bin/gole-backup-data" <<'EOF'
#!/usr/bin/env bash
[ "$1" = "--verify-latest" ] || exit 2
[ "${FAKE_BAD_LOGICAL_BACKUP:-0}" != 1 ]
EOF
chmod 0755 "$TEST_ROOT/bin/gole-backup-data"

export PATH="$TEST_ROOT/bin:$PATH"
export FAKE_GCLOUD_LOG="$TEST_ROOT/gcloud.log"
export GCP_PROJECT_ID="test-project"

# The production verifier calls the fixed root-owned path. Isolate it for this
# runtime contract without changing the production script's trust boundary.
snapshot_verifier="$TEST_ROOT/verify-snapshot-policy.sh"
sed "s#/usr/local/sbin/gole-backup-data#$TEST_ROOT/bin/gole-backup-data#" \
  "$ROOT/infra/gcp/scripts/verify-snapshot-policy.sh" > "$snapshot_verifier"
chmod 0755 "$snapshot_verifier"

bash "$snapshot_verifier"
bash "$snapshot_verifier" --require-ready-snapshot
FAKE_GUEST_FLUSH=missing bash "$snapshot_verifier"
for invalid_flush in true null '"false"' 0; do
  if FAKE_GUEST_FLUSH="$invalid_flush" bash "$snapshot_verifier" >/dev/null 2>&1; then
    echo 'unexpected guest flush value was accepted' >&2
    exit 1
  fi
done

if FAKE_BAD_POLICY=1 bash "$snapshot_verifier" >/dev/null 2>&1; then
  echo "invalid retention policy was accepted" >&2
  exit 1
fi
if FAKE_NO_SNAPSHOT=1 bash "$snapshot_verifier" \
  --require-ready-snapshot >/dev/null 2>&1; then
  echo "missing recovery point was accepted" >&2
  exit 1
fi
if FAKE_NO_DELETION_PROTECTION=1 \
  bash "$snapshot_verifier" >/dev/null 2>&1; then
  echo "disabled VM deletion protection was accepted" >&2
  exit 1
fi
if FAKE_EXTRA_DISK_POLICY=1 \
  bash "$snapshot_verifier" >/dev/null 2>&1; then
  echo "unexpected extra disk policy was accepted" >&2
  exit 1
fi
if FAKE_STALE_INSTANCE_POLICY=1 \
  bash "$snapshot_verifier" >/dev/null 2>&1; then
  echo "stale instance schedule attachment was accepted" >&2
  exit 1
fi
if FAKE_BAD_LOGICAL_BACKUP=1 bash "$snapshot_verifier" \
  --require-ready-snapshot >/dev/null 2>&1; then
  echo "snapshot without a verified logical backup was accepted" >&2
  exit 1
fi
if rg -q '(create|delete|update|attach|add-resource-policies)' "$FAKE_GCLOUD_LOG"; then
  echo "read-only verifier attempted a mutation" >&2
  exit 1
fi

echo "Snapshot policy runtime contract passed."
