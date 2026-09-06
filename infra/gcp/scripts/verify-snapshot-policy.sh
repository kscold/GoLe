#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ID="${GCP_PROJECT_ID:-}"
REGION="${GCP_REGION:-asia-northeast3}"
ZONE="${GCP_INSTANCE_ZONE:-asia-northeast3-a}"
DISK="${GCP_INSTANCE_NAME:-gole-production}"
POLICY="${GOLE_SNAPSHOT_POLICY_NAME:-gole-production-daily-snapshots}"
REQUIRE_READY_SNAPSHOT="false"

if [ "${1:-}" = "--require-ready-snapshot" ] && [ "$#" -eq 1 ]; then
  REQUIRE_READY_SNAPSHOT="true"
elif [ "$#" -ne 0 ]; then
  echo "usage: verify-snapshot-policy.sh [--require-ready-snapshot]" >&2
  exit 2
fi

[[ "$PROJECT_ID" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]] || {
  echo "GCP_PROJECT_ID가 없거나 올바르지 않습니다." >&2
  exit 2
}
[[ "$REGION" =~ ^[a-z]+-[a-z0-9]+[0-9]$ ]] || exit 2
[[ "$ZONE" =~ ^${REGION}-[a-z]$ ]] || exit 2
[[ "$DISK" =~ ^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$ ]] || exit 2
[[ "$POLICY" =~ ^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$ ]] || exit 2

command -v gcloud >/dev/null 2>&1 || {
  echo "gcloud CLI가 필요합니다." >&2
  exit 1
}
command -v jq >/dev/null 2>&1 || {
  echo "jq가 필요합니다." >&2
  exit 1
}

umask 077
work_dir="$(mktemp -d)"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

policy_json="$work_dir/policy.json"
disk_json="$work_dir/disk.json"
instance_json="$work_dir/instance.json"
snapshots_json="$work_dir/snapshots.json"

gcloud compute resource-policies describe "$POLICY" \
  --project "$PROJECT_ID" \
  --region "$REGION" \
  --format=json > "$policy_json"
gcloud compute disks describe "$DISK" \
  --project "$PROJECT_ID" \
  --zone "$ZONE" \
  --format=json > "$disk_json"
gcloud compute instances describe "$DISK" \
  --project "$PROJECT_ID" \
  --zone "$ZONE" \
  --format=json > "$instance_json"

expected_policy="projects/${PROJECT_ID}/regions/${REGION}/resourcePolicies/${POLICY}"
jq -e \
  --arg expected_policy "$expected_policy" \
  --arg region "$REGION" '
    .status == "READY" and
    .snapshotSchedulePolicy.schedule.dailySchedule.daysInCycle == 1 and
    .snapshotSchedulePolicy.schedule.dailySchedule.startTime == "20:00" and
    .snapshotSchedulePolicy.retentionPolicy.maxRetentionDays == 3 and
    .snapshotSchedulePolicy.retentionPolicy.onSourceDiskDelete == "APPLY_RETENTION_POLICY" and
    (.snapshotSchedulePolicy.snapshotProperties |
      (has("guestFlush") | not) or .guestFlush == false) and
    .snapshotSchedulePolicy.snapshotProperties.storageLocations == [$region] and
    .snapshotSchedulePolicy.snapshotProperties.labels.app == "gole" and
    .snapshotSchedulePolicy.snapshotProperties.labels.environment == "production" and
    .snapshotSchedulePolicy.snapshotProperties.labels.backup == "daily" and
    .snapshotSchedulePolicy.snapshotProperties.labels."managed-by" == "terraform" and
    (.selfLink | endswith($expected_policy))
  ' "$policy_json" >/dev/null || {
  echo "스냅샷 일정 또는 보존 정책이 코드 계약과 다릅니다." >&2
  exit 1
}

jq -e --arg expected_policy "$expected_policy" '
  (.resourcePolicies // []) == [$expected_policy] or
  (.resourcePolicies // []) == ["https://www.googleapis.com/compute/v1/" + $expected_policy]
' "$disk_json" >/dev/null || {
  echo "운영 디스크 정책 연결이 코드의 단일 스냅샷 정책과 다릅니다." >&2
  exit 1
}

jq -e '
  .deletionProtection == true and
  ([.disks[]? | select(.boot == true)] | length) == 1 and
  (.resourcePolicies // []) == []
' "$instance_json" >/dev/null || {
  echo "운영 VM 삭제 보호·단일 부팅 디스크·인스턴스 일정 미부착 계약이 다릅니다." >&2
  exit 1
}

if [ "$REQUIRE_READY_SNAPSHOT" = "true" ]; then
  if ! /usr/local/sbin/gole-backup-data --verify-latest >/dev/null; then
    echo "최근 완료된 논리 백업을 검증하지 못해 자동 스냅샷 복원을 신뢰할 수 없습니다." >&2
    exit 1
  fi
  gcloud compute snapshots list \
    --project "$PROJECT_ID" \
    --filter="status=READY" \
    --format=json > "$snapshots_json"
  expected_disk="projects/${PROJECT_ID}/zones/${ZONE}/disks/${DISK}"
  jq -e \
    --arg expected_policy "$expected_policy" \
    --arg expected_disk "$expected_disk" '
      any(.[];
        .status == "READY" and
        (.sourceDisk | endswith($expected_disk)) and
        (.sourceSnapshotSchedulePolicy | endswith($expected_policy))
      )
    ' "$snapshots_json" >/dev/null || {
    echo "운영 디스크의 READY 자동 스냅샷을 찾지 못했습니다." >&2
    exit 1
  }
fi

echo "운영 디스크 스냅샷 정책 검증을 통과했습니다."
