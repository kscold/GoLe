#!/usr/bin/env bash
set -Eeuo pipefail

# Root-only logical recovery point created shortly before the crash-consistent
# Compute Engine snapshot. Paths and containers are fixed so this helper cannot
# become an arbitrary filesystem reader through caller-controlled arguments.
BACKUP_ROOT="/var/backups/gole-data"
DATA_UPGRADE_MARKER_ROOT="/var/backups/gole-images"
MINIO_RECOVERY_MARKER="$BACKUP_ROOT/MINIO_UNFREEZE_REQUIRED"
LOCK_FILE="/run/lock/gole-data-backup.lock"
MAX_AGE_SECONDS=93600
KEEP_COUNT=2
MIN_FREE_KIB=$((10 * 1024 * 1024))
MAX_PAYLOAD_BYTES=$((80 * 1024 * 1024 * 1024))
MONGO_CONTAINER="gole-mongo"
MINIO_CONTAINER="gole-minio"
REDIS_CONTAINER="gole-redis"
MONGO_VOLUME_ROOT="/var/lib/docker/volumes/gole_mongo-data/_data"
MINIO_VOLUME_ROOT="/var/lib/docker/volumes/gole_minio-data/_data"
REDIS_VOLUME_ROOT="/var/lib/docker/volumes/gole_redis-data/_data"
MC_IMAGE="minio/mc:latest@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727"
MINIO_ADMIN_TIMEOUT="30s"
MINIO_ADMIN_KILL_AFTER="5s"
MONGO_ARCHIVE_EXPANSION=4
MONGO_ARCHIVE_OVERHEAD_KIB=$((1024 * 1024))
TAR_ARCHIVE_OVERHEAD_KIB=$((64 * 1024))

die() {
  echo "$*" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || die "logical backup must run as root"
install -d -m 0700 -o root -g root "$BACKUP_ROOT"
install -d -m 0755 -o root -g root /run/lock
exec 9>"$LOCK_FILE"
flock -n 9 || die "another logical backup is active"

run_minio_freeze() {
  # The adopted legacy release uses gole_default, while the new release uses
  # gole_data. Reach only the fixed MinIO container's loopback in either case.
  # mc is the image entrypoint: explicitly select sh for the command sequence.
  timeout --foreground --kill-after="$MINIO_ADMIN_KILL_AFTER" "$MINIO_ADMIN_TIMEOUT" \
    docker run --rm --network "container:$MINIO_CONTAINER" --env-file /etc/gole/infra.env \
    --entrypoint /bin/sh "$MC_IMAGE" -eu -c \
    'mc alias set --api S3v4 local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc admin service freeze local >/dev/null' \
    >/dev/null
}

run_minio_unfreeze_and_prove() {
  # A successful administrative response alone is insufficient after a timed
  # out freeze request: prove the S3 API is serving again before writes can be
  # admitted. `mc ls` is read-only and blocks while the service is frozen.
  # Pin S3v4: alias auto-detection probes S3 and hangs on a frozen service,
  # preventing the administrative unfreeze request from ever being sent.
  timeout --foreground --kill-after="$MINIO_ADMIN_KILL_AFTER" "$MINIO_ADMIN_TIMEOUT" \
    docker run --rm --network "container:$MINIO_CONTAINER" --env-file /etc/gole/infra.env \
    --entrypoint /bin/sh "$MC_IMAGE" -eu -c \
    'mc alias set --api S3v4 local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc admin service unfreeze local >/dev/null && mc ls local >/dev/null' \
    >/dev/null
}

write_minio_recovery_marker() {
  local candidate staging_path="$1"
  [[ "$staging_path" =~ ^${BACKUP_ROOT}/\.staging\.[A-Za-z0-9]+$ ]] || return 1
  [ -d "$staging_path" ] && [ ! -L "$staging_path" ] || return 1
  [ ! -e "$MINIO_RECOVERY_MARKER" ] && [ ! -L "$MINIO_RECOVERY_MARKER" ] || return 1
  candidate="$(mktemp "$BACKUP_ROOT/.minio-recovery.XXXXXX")" || return 1
  printf 'state=unfreeze-required\nstaging_dir=%s\n' "$staging_path" > "$candidate" || {
    rm -f -- "$candidate"
    return 1
  }
  if ! chmod 0600 "$candidate" || ! chown root:root "$candidate" ||
    ! mv -T -- "$candidate" "$MINIO_RECOVERY_MARKER" ||
    ! sync -f "$MINIO_RECOVERY_MARKER" || ! sync -f "$BACKUP_ROOT"; then
    rm -f -- "$candidate"
    return 1
  fi
}

clear_minio_recovery_marker() {
  [ -f "$MINIO_RECOVERY_MARKER" ] && [ ! -L "$MINIO_RECOVERY_MARKER" ] || return 1
  rm -f -- "$MINIO_RECOVERY_MARKER" && sync -f "$BACKUP_ROOT"
}

fail_minio_recovery() {
  systemctl poweroff --no-block >/dev/null 2>&1 || true
  die "$*; VM poweroff requested"
}

read_minio_recovery_marker() {
  local key staging_path value seen_state=0 seen_staging=0
  [ -f "$MINIO_RECOVERY_MARKER" ] && [ ! -L "$MINIO_RECOVERY_MARKER" ] &&
    [ "$(stat -c '%U:%G:%a' "$MINIO_RECOVERY_MARKER")" = root:root:600 ] ||
    fail_minio_recovery "MinIO recovery marker is invalid"
  MINIO_RECOVERY_STAGING=""
  while IFS='=' read -r key value || [ -n "${key}${value}" ]; do
    case "$key" in
      state)
        [ "$seen_state" -eq 0 ] && [ "$value" = unfreeze-required ] ||
          fail_minio_recovery "MinIO recovery marker state is invalid"
        seen_state=1
        ;;
      staging_dir)
        [ "$seen_staging" -eq 0 ] ||
          fail_minio_recovery "MinIO recovery staging is duplicated"
        staging_path="$value"
        seen_staging=1
        ;;
      *) fail_minio_recovery "MinIO recovery marker contains an unknown field" ;;
    esac
  done < "$MINIO_RECOVERY_MARKER"
  [ "$seen_state$seen_staging" = 11 ] ||
    fail_minio_recovery "MinIO recovery marker is incomplete"
  [[ "$staging_path" =~ ^${BACKUP_ROOT}/\.staging\.[A-Za-z0-9]+$ ]] &&
    [ -d "$staging_path" ] && [ ! -L "$staging_path" ] &&
    [ "$(stat -c '%U:%G:%a' "$staging_path")" = root:root:700 ] ||
    fail_minio_recovery "MinIO recovery staging directory is invalid"
  MINIO_RECOVERY_STAGING="$staging_path"
}

recover_uncertain_minio_freeze() {
  [ ! -e "$MINIO_RECOVERY_MARKER" ] && [ ! -L "$MINIO_RECOVERY_MARKER" ] && return 0
  read_minio_recovery_marker
  if ! run_minio_unfreeze_and_prove; then
    fail_minio_recovery "MinIO remains in an uncertain freeze state"
  fi
  clear_minio_recovery_marker ||
    fail_minio_recovery "could not clear the recovered MinIO marker"
  rm -rf -- "$MINIO_RECOVERY_STAGING"
}

apparent_kib() {
  local path="$1" size
  size="$(du -sk --apparent-size --one-file-system -- "$path" | awk 'NR == 1 { print $1 }')" ||
    die "could not measure a data volume"
  [[ "$size" =~ ^[0-9]+$ ]] || die "data volume size is invalid"
  printf '%s\n' "$size"
}

calculate_archive_space_bounds() {
  local available_kib max_payload_kib mongo_source_kib minio_source_kib
  local redis_source_kib required_kib
  mongo_source_kib="$(apparent_kib "$MONGO_VOLUME_ROOT")"
  minio_source_kib="$(apparent_kib "$MINIO_VOLUME_ROOT")"
  redis_source_kib="$(apparent_kib "$REDIS_VOLUME_ROOT")"

  # WiredTiger can be substantially smaller on disk than its exported BSON.
  # A fixed expansion cap plus overhead bounds the writer with RLIMIT_FSIZE;
  # an unexpectedly larger dump fails inside already-reserved extents.
  MONGO_ARCHIVE_BOUND_KIB=$((
    mongo_source_kib * MONGO_ARCHIVE_EXPANSION + MONGO_ARCHIVE_OVERHEAD_KIB
  ))
  MINIO_ARCHIVE_BOUND_KIB=$((
    minio_source_kib + minio_source_kib / 20 + TAR_ARCHIVE_OVERHEAD_KIB
  ))
  REDIS_ARCHIVE_BOUND_KIB=$((
    redis_source_kib + redis_source_kib / 20 + TAR_ARCHIVE_OVERHEAD_KIB
  ))
  TOTAL_ARCHIVE_BOUND_KIB=$((
    MONGO_ARCHIVE_BOUND_KIB + MINIO_ARCHIVE_BOUND_KIB + REDIS_ARCHIVE_BOUND_KIB
  ))
  max_payload_kib=$((MAX_PAYLOAD_BYTES / 1024))
  [ "$TOTAL_ARCHIVE_BOUND_KIB" -le "$max_payload_kib" ] ||
    die "source data exceeds the reviewed logical backup bound"

  # df already includes live source volumes and every retained/pinned backup.
  # Requiring the complete new staging bound on top of that occupied space,
  # plus the fixed 10 GiB recovery floor, covers the three-backup peak before
  # rotation. fallocate below turns this estimate into an allocation guarantee
  # before MinIO is frozen.
  available_kib="$(df -Pk "$BACKUP_ROOT" | awk 'NR == 2 { print $4 }')"
  [[ "$available_kib" =~ ^[0-9]+$ ]] || die "boot-disk free space is invalid"
  required_kib=$((TOTAL_ARCHIVE_BOUND_KIB + MIN_FREE_KIB))
  [ "$available_kib" -ge "$required_kib" ] ||
    die "insufficient boot-disk space for bounded backup staging and recovery floor"
}

reserve_archive_space() {
  fallocate --length "$((MONGO_ARCHIVE_BOUND_KIB * 1024))" \
    "$staging_dir/mongo.archive.gz" &&
    fallocate --length "$((MINIO_ARCHIVE_BOUND_KIB * 1024))" \
      "$staging_dir/minio.tar.gz" &&
    fallocate --length "$((REDIS_ARCHIVE_BOUND_KIB * 1024))" \
      "$staging_dir/redis.tar.gz"
}

verify_archive_reservation() {
  local allocated_kib=0 artifact available_kib size
  for artifact in mongo.archive.gz minio.tar.gz redis.tar.gz; do
    size="$(du -sk -- "$staging_dir/$artifact" | awk 'NR == 1 { print $1 }')"
    [[ "$size" =~ ^[0-9]+$ ]] || return 1
    allocated_kib=$((allocated_kib + size))
  done
  [ "$allocated_kib" -ge "$TOTAL_ARCHIVE_BOUND_KIB" ] || return 1
  available_kib="$(df -Pk "$BACKUP_ROOT" | awk 'NR == 2 { print $4 }')"
  [[ "$available_kib" =~ ^[0-9]+$ ]] && [ "$available_kib" -ge "$MIN_FREE_KIB" ]
}

write_bounded_archive() {
  local archive_fd destination="$1" limit_kib="$2" position status=0
  shift 2
  exec {archive_fd}<> "$destination"
  if (ulimit -c 0 && ulimit -f "$limit_kib" && "$@") >&"$archive_fd"; then
    :
  else
    status=$?
  fi
  position="$(awk '$1 == "pos:" { print $2 }' "/proc/$$/fdinfo/$archive_fd")"
  exec {archive_fd}>&-
  [[ "$position" =~ ^[0-9]+$ ]] &&
    [ "$position" -le $((limit_kib * 1024)) ] || status=1
  truncate --size "$position" "$destination" || return 1
  return "$status"
}

latest_complete() {
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -xdev -type d \
    -name '20??????T??????Z' -printf '%f\n' | LC_ALL=C sort -r | head -n 1
}

verify_directory() {
  local directory="$1" manifest
  [[ "$directory" =~ ^${BACKUP_ROOT}/20[0-9]{6}T[0-9]{6}Z$ ]] ||
    die "logical backup directory is invalid"
  [ -d "$directory" ] && [ ! -L "$directory" ] || die "logical backup is missing"
  [ "$(stat -c '%U:%G:%a' "$directory")" = "root:root:700" ] ||
    die "logical backup permissions are invalid"
  manifest="$directory/COMPLETE"
  [ -f "$manifest" ] && [ ! -L "$manifest" ] || die "logical backup completion marker is missing"
  [ "$(stat -c '%U:%G:%a' "$manifest")" = "root:root:600" ] ||
    die "logical backup completion marker permissions are invalid"
  (
    cd "$directory"
    sha256sum --check --strict --status SHA256SUMS &&
      [ -s mongo.archive.gz ] &&
      [ -s minio.tar.gz ] &&
    [ -s redis.tar.gz ]
  ) || die "logical backup checksum validation failed"
}

declare -A PINNED_BACKUPS=()
load_active_deployment_pins() {
  local backup_path marker name
  [ -d "$DATA_UPGRADE_MARKER_ROOT" ] || return 0
  while IFS= read -r -d '' marker; do
    [ -f "$marker" ] && [ ! -L "$marker" ] &&
      [ "$(stat -c '%U:%G:%a' "$marker")" = root:root:600 ] ||
      die "active data upgrade marker is invalid"
    backup_path="$(awk -F= '
      $1 == "backup_path" { count += 1; value = substr($0, length($1) + 2) }
      END { if (count != 1) exit 1; print value }
    ' "$marker")" || die "active data upgrade marker has no exact backup path"
    [[ "$backup_path" =~ ^${BACKUP_ROOT}/20[0-9]{6}T[0-9]{6}Z$ ]] ||
      die "active data upgrade marker backup path is invalid"
    verify_directory "$backup_path"
    name="${backup_path##*/}"
    PINNED_BACKUPS["$name"]=1
  done < <(find "$DATA_UPGRADE_MARKER_ROOT" -mindepth 1 -maxdepth 1 -xdev \
    -name 'data-upgrade.*' -print0)
}

if [ "${1:-}" = "--recover-minio" ] && [ "$#" -eq 1 ]; then
  [ -e "$MINIO_RECOVERY_MARKER" ] || [ -L "$MINIO_RECOVERY_MARKER" ] ||
    die "no MinIO unfreeze recovery is pending"
  recover_uncertain_minio_freeze
  exit 0
fi
if [ "${1:-}" = "--verify-latest" ] && [ "$#" -eq 1 ]; then
  [ ! -e "$MINIO_RECOVERY_MARKER" ] && [ ! -L "$MINIO_RECOVERY_MARKER" ] ||
    fail_minio_recovery "MinIO unfreeze recovery is still required"
  latest="$(latest_complete)"
  [ -n "$latest" ] || die "no completed logical backup exists"
  verify_directory "$BACKUP_ROOT/$latest"
  age=$(( $(date -u +%s) - $(stat -c '%Y' "$BACKUP_ROOT/$latest/COMPLETE") ))
  [ "$age" -ge 0 ] && [ "$age" -le "$MAX_AGE_SECONDS" ] ||
    die "the latest logical backup is stale"
  exit 0
fi
[ "$#" -eq 0 ] || die "usage: backup-data.sh [--verify-latest|--recover-minio]"

recover_uncertain_minio_freeze

for container in "$MONGO_CONTAINER" "$MINIO_CONTAINER" "$REDIS_CONTAINER"; do
  state="$(docker inspect --format '{{.State.Status}}:{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \
    "$container" 2>/dev/null || true)"
  [ "$state" = "running:healthy" ] || die "required data container is not healthy"
done
for volume_root in "$MONGO_VOLUME_ROOT" "$MINIO_VOLUME_ROOT" "$REDIS_VOLUME_ROOT"; do
  [ -d "$volume_root" ] && [ ! -L "$volume_root" ] ||
    die "a fixed data volume is missing or unsafe"
done
calculate_archive_space_bounds

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final_dir="$BACKUP_ROOT/$stamp"
[ ! -e "$final_dir" ] && [ ! -L "$final_dir" ] || die "logical backup already exists"
staging_dir="$(mktemp -d "$BACKUP_ROOT/.staging.XXXXXX")"
chmod 0700 "$staging_dir"
minio_unfreeze_required=0
minio_unfreeze_proven=0
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [ "$minio_unfreeze_required" -eq 1 ] &&
    [ ! -e "$MINIO_RECOVERY_MARKER" ] && [ ! -L "$MINIO_RECOVERY_MARKER" ]; then
    # The only post-freeze path that removes the durable marker has already
    # completed the unfreeze plus S3 proof. This closes the signal window
    # between the durable removal and the in-memory flag updates below.
    minio_unfreeze_proven=1
    minio_unfreeze_required=0
  fi
  if [ "$minio_unfreeze_required" -eq 1 ] && [ "$minio_unfreeze_proven" -eq 0 ]; then
    if run_minio_unfreeze_and_prove >/dev/null 2>&1 &&
      clear_minio_recovery_marker >/dev/null 2>&1; then
      minio_unfreeze_proven=1
      minio_unfreeze_required=0
    else
      status=78
      systemctl poweroff --no-block >/dev/null 2>&1 || true
      for reserved_artifact in mongo.archive.gz minio.tar.gz redis.tar.gz; do
        [ ! -f "$staging_dir/$reserved_artifact" ] ||
          timeout 5s fallocate --dig-holes "$staging_dir/$reserved_artifact" \
            >/dev/null 2>&1 || true
      done
      echo "MinIO unfreeze could not be proven; recovery staging retained at $staging_dir; VM poweroff requested" >&2
    fi
  fi
  if [ "$minio_unfreeze_required" -eq 0 ] && [ -n "$staging_dir" ]; then
    rm -rf -- "$staging_dir"
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Reserve the complete worst-case staging footprint before any freeze side
# effect. All archive writers below are also file-size bounded to these exact
# extents, so an underestimated compression ratio cannot consume the 10 GiB
# recovery floor or starve the live database filesystem.
reserve_archive_space || die "could not reserve bounded logical backup staging"
verify_archive_reservation ||
  die "logical backup reservation did not preserve the recovery floor"

# Arm recovery before sending the freeze request. The service can apply the
# side effect even when the client times out or loses the response.
minio_unfreeze_required=1
write_minio_recovery_marker "$staging_dir" ||
  die "could not durably arm MinIO freeze recovery"
run_minio_freeze || die "MinIO freeze could not be confirmed"

# --oplog produces a point-in-time-consistent full replica-set dump and avoids
# a container temporary file that could remain after interruption.
write_bounded_archive "$staging_dir/mongo.archive.gz" "$MONGO_ARCHIVE_BOUND_KIB" \
  timeout 10m docker exec "$MONGO_CONTAINER" mongodump --archive --gzip --oplog
timeout 2m docker exec "$REDIS_CONTAINER" redis-cli SAVE >/dev/null
write_bounded_archive "$staging_dir/redis.tar.gz" "$REDIS_ARCHIVE_BOUND_KIB" \
  timeout 10m tar --one-file-system --numeric-owner -C "$REDIS_VOLUME_ROOT" -czf - .
write_bounded_archive "$staging_dir/minio.tar.gz" "$MINIO_ARCHIVE_BOUND_KIB" \
  timeout 10m tar --one-file-system --numeric-owner -C "$MINIO_VOLUME_ROOT" -czf - .
run_minio_unfreeze_and_prove || die "MinIO unfreeze could not be proven"
clear_minio_recovery_marker || die "could not clear the MinIO recovery marker"
minio_unfreeze_proven=1
minio_unfreeze_required=0

mongo_size="$(stat -c '%s' "$staging_dir/mongo.archive.gz")"
minio_size="$(stat -c '%s' "$staging_dir/minio.tar.gz")"
redis_size="$(stat -c '%s' "$staging_dir/redis.tar.gz")"
[ "$mongo_size" -ge 1024 ] && [ "$minio_size" -ge 1024 ] &&
  [ "$redis_size" -ge 1 ] &&
  [ $((mongo_size + minio_size + redis_size)) -le "$MAX_PAYLOAD_BYTES" ] ||
  die "logical backup payload size is outside the reviewed bounds"

(
  cd "$staging_dir"
  sha256sum mongo.archive.gz minio.tar.gz redis.tar.gz > SHA256SUMS
)
chmod 0600 "$staging_dir"/*
chown -R root:root "$staging_dir"
# Persist payloads, checksums and their directory entries before creating the
# completion marker. guest_flush=false snapshots may begin immediately after
# this service returns, so page-cache-only success is not sufficient.
sync -f "$staging_dir/mongo.archive.gz"
sync -f "$staging_dir/minio.tar.gz"
sync -f "$staging_dir/redis.tar.gz"
sync -f "$staging_dir/SHA256SUMS"
sync -f "$staging_dir"
printf 'format=gole-logical-backup-v1\ncreated_at=%s\n' "$stamp" > "$staging_dir/COMPLETE"
chmod 0600 "$staging_dir/COMPLETE"
chown root:root "$staging_dir/COMPLETE"
sync -f "$staging_dir/COMPLETE"
sync -f "$staging_dir"
mv -- "$staging_dir" "$final_dir"
staging_dir=""
sync -f "$BACKUP_ROOT"
trap - EXIT INT TERM

verify_directory "$final_dir"
available_kib="$(df -Pk "$BACKUP_ROOT" | awk 'NR == 2 {print $4}')"
[[ "$available_kib" =~ ^[0-9]+$ ]] && [ "$available_kib" -ge "$MIN_FREE_KIB" ] ||
  die "logical backup completed but boot-disk free space is below the safe floor"

# Keep exactly the two newest completed local artifacts. Every automatic disk
# snapshot contains the two artifacts current at snapshot time; retaining more
# locally only inflates future snapshot deltas without increasing the three-day
# snapshot recovery-point count.
load_active_deployment_pins
mapfile -t completed_backups < <(
  find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -xdev -type d \
    -name '20??????T??????Z' -printf '%f\n' | LC_ALL=C sort -r
)
for old_name in "${completed_backups[@]:$KEEP_COUNT}"; do
  [[ "$old_name" =~ ^20[0-9]{6}T[0-9]{6}Z$ ]] ||
    die "logical backup rotation selected an invalid path"
  old_directory="$BACKUP_ROOT/$old_name"
  # A deployment may remain recoverable for days after a host interruption.
  # Keep its exact logical backup even when it is older than the normal two
  # rolling recovery points; terminal transaction cleanup releases the pin.
  if [ "${PINNED_BACKUPS[$old_name]:-0}" -eq 1 ]; then
    continue
  fi
  [ -d "$old_directory" ] && [ ! -L "$old_directory" ] ||
    die "logical backup rotation target is unsafe"
  find "$old_directory" -xdev -depth -delete
done

# The deployment transaction captures this fixed-root path and independently
# revalidates its permissions and checksums before changing a data image.
printf '%s\n' "$final_dir"
