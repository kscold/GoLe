#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
IMAGE="ubuntu@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517"

docker run --rm --interactive \
  --volume "$ROOT:/source:ro" \
  "$IMAGE" bash -seu <<'CONTAINER_TEST'
install -d -m 0755 /test-bin /usr/local/sbin \
  /var/lib/docker/volumes/gole_mongo-data/_data \
  /var/lib/docker/volumes/gole_minio-data/_data \
  /var/lib/docker/volumes/gole_redis-data/_data
dd if=/dev/urandom of=/var/lib/docker/volumes/gole_mongo-data/_data/collection.wt \
  bs=2048 count=1 status=none
dd if=/dev/urandom of=/var/lib/docker/volumes/gole_minio-data/_data/object.bin \
  bs=2048 count=1 status=none
dd if=/dev/urandom of=/var/lib/docker/volumes/gole_redis-data/_data/dump.rdb \
  bs=2048 count=1 status=none
install -m 0755 /source/infra/gcp/scripts/backup-data.sh /usr/local/sbin/gole-backup-data

cat > /test-bin/docker <<'FAKE_DOCKER'
#!/bin/sh
set -eu
case "$1" in
  inspect)
    printf 'running:healthy\n'
    ;;
  exec)
    if [ "$2" = gole-redis ]; then
      printf 'redis-save\n' >> /tmp/backup-events
      exit 0
    fi
    printf 'mongo-export\n' >> /tmp/backup-events
    [ ! -e /tmp/fail-mongo ] || exit 71
    dd if=/dev/zero bs=2048 count=1 status=none
    ;;
  run)
    case "$*" in
      *'--network container:gole-minio --env-file /etc/gole/infra.env --entrypoint /bin/sh '*'http://127.0.0.1:9000'*) ;;
      *) echo 'MinIO helper must use the target network namespace and shell entrypoint' >&2; exit 79 ;;
    esac
    case "$*" in
      *'service freeze'*)
        printf 'freeze\n' >> /tmp/backup-events
        [ ! -e /tmp/fail-freeze-response ] || exit 74
        ;;
      *'service unfreeze'*)
        printf 'unfreeze\n' >> /tmp/backup-events
        case "$*" in *'mc ls local'*) ;; *) exit 75 ;; esac
        [ ! -e /tmp/fail-unfreeze ] || exit 76
        ;;
      *) exit 72 ;;
    esac
    ;;
  *) exit 73 ;;
esac
FAKE_DOCKER
cat > /test-bin/df <<'FAKE_DF'
#!/bin/sh
set -eu
if [ -e /tmp/low-backup-space ]; then
  printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n'
  printf 'test 104857600 94371840 10485760 90%% /\n'
  exit 0
fi
exec /usr/bin/df "$@"
FAKE_DF
cat > /test-bin/fallocate <<'FAKE_FALLOCATE'
#!/bin/bash
set -eu
case " $* " in
  *' --length '*)
    printf 'reserve-%s\n' "${@: -1}" >> /tmp/backup-events
    [ ! -e /tmp/fail-backup-reservation ] || exit 77
    ;;
esac
exec /usr/bin/fallocate "$@"
FAKE_FALLOCATE
cat > /test-bin/timeout <<'FAKE_TIMEOUT'
#!/bin/sh
set -eu
case "$*" in
  *'service freeze'*)
    printf 'timeout-freeze\n' >> /tmp/backup-events
    if [ -e /tmp/simulate-freeze-timeout ]; then
      printf 'freeze\n' >> /tmp/backup-events
      exit 124
    fi
    ;;
  *'service unfreeze'*) printf 'timeout-unfreeze\n' >> /tmp/backup-events ;;
esac
exec /usr/bin/timeout "$@"
FAKE_TIMEOUT
cat > /test-bin/tar <<'FAKE_TAR'
#!/bin/sh
case "$*" in
  *gole_redis-data*) printf 'redis-archive\n' >> /tmp/backup-events ;;
  *) printf 'minio-archive\n' >> /tmp/backup-events ;;
esac
exec /usr/bin/tar "$@"
FAKE_TAR
cat > /test-bin/sync <<'FAKE_SYNC'
#!/bin/sh
printf 'sync %s\n' "$*" >> /tmp/backup-events
exec /usr/bin/sync "$@"
FAKE_SYNC
cat > /test-bin/systemctl <<'FAKE_SYSTEMCTL'
#!/bin/sh
case "$1" in
  poweroff) touch /tmp/poweroff-requested ;;
  *) exit 1 ;;
esac
FAKE_SYSTEMCTL
chmod 0755 /test-bin/*
export PATH="/test-bin:$PATH"

# Existing source/backup usage is already reflected by df; the complete new
# bounded payload and the 10 GiB recovery floor must additionally fit before
# staging allocation or any MinIO side effect.
: > /tmp/backup-events
touch /tmp/low-backup-space
if /usr/local/sbin/gole-backup-data >/tmp/low-space.out 2>&1; then
  echo 'backup accepted insufficient peak disk space' >&2
  exit 1
fi
rm -f /tmp/low-backup-space
grep -q 'bounded backup staging and recovery floor' /tmp/low-space.out
! grep -q '^freeze$' /tmp/backup-events
! find /var/backups/gole-data -maxdepth 1 -type d -name '.staging.*' -print -quit | grep -q .

# A successful arithmetic preflight is not enough: failure to physically
# reserve the extents must also abort and clean staging before freeze.
: > /tmp/backup-events
touch /tmp/fail-backup-reservation
if /usr/local/sbin/gole-backup-data >/tmp/reservation-failure.out 2>&1; then
  echo 'backup ignored an extent reservation failure' >&2
  exit 1
fi
rm -f /tmp/fail-backup-reservation
grep -q '^reserve-' /tmp/backup-events
! grep -q '^freeze$' /tmp/backup-events
! find /var/backups/gole-data -maxdepth 1 -type d -name '.staging.*' -print -quit | grep -q .

# A timed-out freeze may already have applied on the server. Recovery is armed
# durably before the request, so the EXIT path still unfreezes and proves S3
# responsiveness. The failed backup must not leave a stale marker or staging.
: > /tmp/backup-events
touch /tmp/simulate-freeze-timeout
if /usr/local/sbin/gole-backup-data >/tmp/freeze-timeout.out 2>&1; then
  echo 'timed-out MinIO freeze unexpectedly succeeded' >&2
  exit 1
fi
rm -f /tmp/simulate-freeze-timeout
[ "$(grep -c '^freeze$' /tmp/backup-events)" = 1 ]
[ "$(grep -c '^unfreeze$' /tmp/backup-events)" = 1 ]
[ "$(grep -c '^timeout-freeze$' /tmp/backup-events)" = 1 ]
[ "$(grep -c '^timeout-unfreeze$' /tmp/backup-events)" = 1 ]
reservation_line="$(grep -n '^reserve-' /tmp/backup-events | tail -1 | cut -d: -f1)"
freeze_timeout_line="$(grep -n '^timeout-freeze$' /tmp/backup-events | cut -d: -f1)"
[ "$(grep -c '^reserve-' /tmp/backup-events)" = 3 ]
[ "$reservation_line" -lt "$freeze_timeout_line" ]
[ ! -e /var/backups/gole-data/MINIO_UNFREEZE_REQUIRED ]
! find /var/backups/gole-data -maxdepth 1 -type d -name '.staging.*' -print -quit | grep -q .

# A freeze that applies but returns failure exercises the same pre-armed path.
: > /tmp/backup-events
touch /tmp/fail-freeze-response
if /usr/local/sbin/gole-backup-data >/tmp/freeze-response.out 2>&1; then
  echo 'failed MinIO freeze response unexpectedly succeeded' >&2
  exit 1
fi
rm -f /tmp/fail-freeze-response
[ "$(grep -c '^freeze$' /tmp/backup-events)" = 1 ]
[ "$(grep -c '^unfreeze$' /tmp/backup-events)" = 1 ]
[ ! -e /var/backups/gole-data/MINIO_UNFREEZE_REQUIRED ]

# A failed Mongo export occurs while MinIO is frozen. The EXIT trap must
# unfreeze it and no completion marker may survive.
: > /tmp/backup-events
: > /tmp/fail-mongo
if /usr/local/sbin/gole-backup-data >/tmp/backup-failure.out 2>&1; then
  echo 'failed logical backup unexpectedly succeeded' >&2
  exit 1
fi
[ "$(grep -c '^freeze$' /tmp/backup-events)" = 1 ]
[ "$(grep -c '^unfreeze$' /tmp/backup-events)" = 1 ]
! find /var/backups/gole-data -name COMPLETE -print -quit | grep -q .
rm -f /tmp/fail-mongo

# If both the normal unfreeze and the EXIT retry fail, uncertainty is durable:
# the incomplete payload remains, verification refuses it, and the VM is told
# to stop. A later root-only recovery retries unfreeze plus the S3 proof before
# removing either recovery artifact.
: > /tmp/backup-events
rm -f /tmp/poweroff-requested
touch /tmp/fail-mongo /tmp/fail-unfreeze
if /usr/local/sbin/gole-backup-data >/tmp/unfreeze-uncertain.out 2>&1; then
  echo 'unproven MinIO unfreeze unexpectedly succeeded' >&2
  exit 1
fi
[ -f /var/backups/gole-data/MINIO_UNFREEZE_REQUIRED ]
[ "$(stat -c '%U:%G:%a' /var/backups/gole-data/MINIO_UNFREEZE_REQUIRED)" = root:root:600 ]
[ "$(find /var/backups/gole-data -maxdepth 1 -type d -name '.staging.*' | wc -l)" = 1 ]
[ -e /tmp/poweroff-requested ]
if /usr/local/sbin/gole-backup-data --verify-latest >/tmp/pending-unfreeze.out 2>&1; then
  echo 'backup verification ignored pending MinIO recovery' >&2
  exit 1
fi
rm -f /tmp/fail-mongo /tmp/fail-unfreeze /tmp/poweroff-requested
/usr/local/sbin/gole-backup-data --recover-minio
[ ! -e /var/backups/gole-data/MINIO_UNFREEZE_REQUIRED ]
! find /var/backups/gole-data -maxdepth 1 -type d -name '.staging.*' -print -quit | grep -q .

# Seed old directories to prove rotation is count-based rather than mtime
# rounding, while an active deployment transaction pins its exact older
# checksummed recovery point beyond the normal newest-two retention window.
install -d -m 0700 /var/backups/gole-data/20200101T000000Z \
  /var/backups/gole-data/20210101T000000Z \
  /var/backups/gole-data/20220101T000000Z
printf 'pinned-mongo\n' > /var/backups/gole-data/20200101T000000Z/mongo.archive.gz
printf 'pinned-minio\n' > /var/backups/gole-data/20200101T000000Z/minio.tar.gz
printf 'pinned-redis\n' > /var/backups/gole-data/20200101T000000Z/redis.tar.gz
(
  cd /var/backups/gole-data/20200101T000000Z
  sha256sum mongo.archive.gz minio.tar.gz redis.tar.gz > SHA256SUMS
)
printf 'format=gole-logical-backup-v1\ncreated_at=20200101T000000Z\n' \
  > /var/backups/gole-data/20200101T000000Z/COMPLETE
chmod 0600 /var/backups/gole-data/20200101T000000Z/*
install -d -m 0700 /var/backups/gole-images
cat > /var/backups/gole-images/data-upgrade.10000000000040008000000000000001 <<'EOF'
request_id=10000000-0000-4000-8000-000000000001
backup_path=/var/backups/gole-data/20200101T000000Z
EOF
chmod 0600 /var/backups/gole-images/data-upgrade.10000000000040008000000000000001
: > /tmp/backup-events
latest_path="$(/usr/local/sbin/gole-backup-data)"
/usr/local/sbin/gole-backup-data --verify-latest
[ -s "$latest_path/redis.tar.gz" ]
[ "$(find /var/backups/gole-data -mindepth 1 -maxdepth 1 -type d \
  -name '20??????T??????Z' | wc -l)" = 3 ]
[ -f /var/backups/gole-data/20200101T000000Z/COMPLETE ]
[ ! -e /var/backups/gole-data/20210101T000000Z ]

freeze_line="$(grep -n '^freeze$' /tmp/backup-events | cut -d: -f1)"
mongo_line="$(grep -n '^mongo-export$' /tmp/backup-events | cut -d: -f1)"
archive_line="$(grep -n '^minio-archive$' /tmp/backup-events | cut -d: -f1)"
redis_line="$(grep -n '^redis-save$' /tmp/backup-events | cut -d: -f1)"
redis_archive_line="$(grep -n '^redis-archive$' /tmp/backup-events | cut -d: -f1)"
unfreeze_line="$(grep -n '^unfreeze$' /tmp/backup-events | cut -d: -f1)"
payload_sync_line="$(grep -n 'sync .*mongo.archive.gz' /tmp/backup-events | cut -d: -f1)"
complete_sync_line="$(grep -n 'sync .*COMPLETE' /tmp/backup-events | cut -d: -f1)"
[ "$freeze_line" -lt "$mongo_line" ]
[ "$mongo_line" -lt "$redis_line" ]
[ "$redis_line" -lt "$redis_archive_line" ]
[ "$redis_archive_line" -lt "$archive_line" ]
[ "$archive_line" -lt "$unfreeze_line" ]
[ "$payload_sync_line" -lt "$complete_sync_line" ]
[ "$(grep -c '^timeout-freeze$' /tmp/backup-events)" = 1 ]
[ "$(grep -c '^timeout-unfreeze$' /tmp/backup-events)" = 1 ]

printf 'checksum-drift\n' >> "$latest_path/mongo.archive.gz"
if /usr/local/sbin/gole-backup-data --verify-latest >/tmp/checksum-drift.out 2>&1; then
  echo 'backup verifier accepted a corrupted payload' >&2
  exit 1
fi

# The systemd deadline bounds the 19:30 UTC timer run before the 20:00 UTC
# Compute Engine snapshot and leaves a full minute for the EXIT unfreeze trap.
grep -Fxq 'OnCalendar=*-*-* 19:30:00 UTC' \
  /source/infra/gcp/systemd/gole-data-backup.timer
grep -Fxq 'TimeoutStartSec=25min' \
  /source/infra/gcp/systemd/gole-data-backup.service
grep -Fxq 'TimeoutStopSec=1min' \
  /source/infra/gcp/systemd/gole-data-backup.service

echo 'Logical backup freeze, durability, verification, and rotation tests passed.'
CONTAINER_TEST
