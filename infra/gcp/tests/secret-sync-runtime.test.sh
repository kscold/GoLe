#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
IMAGE="python@sha256:9d2e5553305c7c7b0097999bb17187c69b921ccd6bc9d40e4bb5ebe652c00285"

docker run --rm --interactive \
  --volume "$ROOT:/source:ro" \
  "$IMAGE" bash -seu <<'CONTAINER_TEST'
sha='1111111111111111111111111111111111111111'
release="/var/lib/gole/releases/$sha"
install -d -m 0755 /app/infra/gcp /etc/gole /usr/local/libexec/gole \
  /usr/local/sbin /usr/local/bin "$release/infra/gcp"
printf 'root:root\n' > /etc/gole/deploy-user
printf 'MINIO_ROOT_USER=test\nMINIO_ROOT_PASSWORD=test-password\n' > /etc/gole/infra.env
install -m 0600 -o root -g root /source/infra/gcp/tests/fixtures/discord.env \
  /etc/gole/discord.env
printf 'PROJECT_ID=test-project-123\n' > /etc/gole/cloud-broker.conf
chmod 0600 /etc/gole/infra.env /etc/gole/cloud-broker.conf
install -m 0755 /source/infra/gcp/scripts/gole-hostctl.sh /usr/local/sbin/gole-hostctl
install -m 0755 /source/infra/gcp/scripts/validate-production-env.py \
  /usr/local/libexec/gole/validate-production-env.py
ln -s /usr/local/bin/python3 /usr/bin/python3
printf '#!/bin/sh\ncat >/dev/null\n' > /usr/local/libexec/gole/validate-production-compose.py
chmod 0755 /usr/local/libexec/gole/*.py
touch "$release/infra/gcp/docker-compose.yml"
touch /app/infra/gcp/docker-compose.yml
printf '%s\n' "$sha" > "$release/.gole-source-sha"
chown -R root:root /var/lib/gole
chmod -R go-w /var/lib/gole

install -d /fixtures
install -m 0600 /source/infra/gcp/tests/fixtures/production.env /fixtures/base.env
cp /fixtures/base.env /fixtures/credentialed-smtp.env
sed -i 's/^SMTP_PASSWORD=$/SMTP_PASSWORD=legacy-private-app-password/' \
  /fixtures/credentialed-smtp.env
cp /fixtures/base.env /fixtures/v6.env
printf 'SAFE_POLICY_REVISION=6\n' >> /fixtures/v6.env
cp /fixtures/base.env /fixtures/v8.env
printf 'SAFE_POLICY_REVISION=8\n' >> /fixtures/v8.env
cp /fixtures/base.env /fixtures/v9.env
printf 'SAFE_POLICY_REVISION=9\n' >> /fixtures/v9.env
install -m 0600 /source/infra/gcp/tests/fixtures/development.env /fixtures/development.env

cat > /usr/bin/gcloud <<'FAKE_GCLOUD'
#!/bin/sh
set -eu
[ "$1" = secrets ] && [ "$2" = versions ] && [ "$3" = access ] || exit 91
version="$4"
shift 4
output=''
for argument in "$@"; do
  case "$argument" in
    --secret=gole-production-env|--project=test-project-123|--quiet) ;;
    --out-file=*) output="${argument#--out-file=}" ;;
    *) exit 92 ;;
  esac
done
[ -n "$output" ] || exit 93
printf 'called:%s\n' "$version" >> /tmp/gcloud-calls
case "$version" in
  5) cp /fixtures/base.env "$output" ;;
  6) cp /fixtures/v6.env "$output" ;;
  7) cp /fixtures/development.env "$output" ;;
  8) cp /fixtures/v8.env "$output" ;;
  9) cp /fixtures/v9.env "$output" ;;
  10) cp /fixtures/credentialed-smtp.env "$output" ;;
  *) exit 94 ;;
esac
FAKE_GCLOUD

cat > /usr/local/bin/docker <<'FAKE_DOCKER'
#!/bin/sh
set -eu
printf '%s\n' "$*" >> /tmp/docker-calls

image_id_for_service() {
  case "$1" in
    mongo|mongo-init) printf 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n' ;;
    redis) printf 'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n' ;;
    minio) printf 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\n' ;;
    minio-init) printf 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\n' ;;
    support-agent) printf 'sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\n' ;;
    backend) printf 'sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff\n' ;;
    frontend) printf 'sha256:1111111111111111111111111111111111111111111111111111111111111111\n' ;;
    nginx) printf 'sha256:2222222222222222222222222222222222222222222222222222222222222222\n' ;;
    budget-relay) printf 'sha256:3333333333333333333333333333333333333333333333333333333333333333\n' ;;
    *) exit 96 ;;
  esac
}

service_for_image_ref() {
  case "$1" in
    mongo:7) printf 'mongo\n' ;;
    redis:7-alpine) printf 'redis\n' ;;
    minio/minio:latest) printf 'minio\n' ;;
    minio/mc:latest) printf 'minio-init\n' ;;
    gole/support-agent:local) printf 'support-agent\n' ;;
    gole/backend:local) printf 'backend\n' ;;
    gole/frontend:local) printf 'frontend\n' ;;
    nginx:1.29-alpine) printf 'nginx\n' ;;
    gole/budget-relay:local) printf 'budget-relay\n' ;;
    *) exit 97 ;;
  esac
}

case "$1" in
  compose)
    case "$*" in
      *' config --format json')
        printf '%s\n' '{"services":{"mongo":{"image":"mongo:7"},"mongo-init":{"image":"mongo:7"},"redis":{"image":"redis:7-alpine"},"minio":{"image":"minio/minio:latest"},"minio-init":{"image":"minio/mc:latest"},"support-agent":{"image":"gole/support-agent:local"},"backend":{"image":"gole/backend:local"},"frontend":{"image":"gole/frontend:local"},"nginx":{"image":"nginx:1.29-alpine"},"budget-relay":{"image":"gole/budget-relay:local"}}}'
        ;;
      *' config --services')
        printf '%s\n' mongo mongo-init redis minio minio-init support-agent backend frontend nginx budget-relay
        ;;
      *' ps -a -q '*)
        for service do :; done
        printf 'gole-%s\n' "$service"
        ;;
      *' up -d '*)
        for service do :; done
        environment_hash="$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)"
        printf '%s:%s\n' "$environment_hash" "$service" >> /tmp/restart-trace
        if [ -e /tmp/fail-up-once ]; then
          rm -f /tmp/fail-up-once
          exit 50
        fi
        ;;
      *' exec -T nginx nginx -t'|*' exec -T nginx nginx -s reload') ;;
      *) exit 98 ;;
    esac
    ;;
  image)
    [ "$2" = inspect ] || exit 99
    for image_ref do :; done
    service="$(service_for_image_ref "$image_ref")"
    printf 'expected-image:%s\n' "$service" >> /tmp/provenance-trace
    image_id_for_service "$service"
    ;;
  inspect)
    for container do :; done
    service="${container#gole-}"
    case "$*" in
      *'com.docker.compose.project'*)
        printf 'ownership:%s\n' "$service" >> /tmp/provenance-trace
        printf 'gole|%s\n' "$service"
        ;;
      *'{{.State.Status}}:{{if .State.Health}'*) printf 'running:healthy\n' ;;
      *'{{.Image}}'*)
        printf 'actual-image:%s\n' "$service" >> /tmp/provenance-trace
        image_id_for_service "$service"
        ;;
      *'NetworkSettings.Networks'*)
        printf 'networks:%s\n' "$service" >> /tmp/provenance-trace
        case "$service" in
          backend) printf 'gole_agent\ngole_data\ngole_edge\n' ;;
          support-agent) printf 'gole_agent\n' ;;
          mongo|redis|minio) printf 'gole_data\n' ;;
          frontend|nginx|budget-relay) printf 'gole_edge\n' ;;
          *) exit 100 ;;
        esac
        ;;
      *'HostConfig.PortBindings'*)
        printf 'ports:%s\n' "$service" >> /tmp/provenance-trace
        case "$service" in
          backend) printf '%s\n' '{"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"8080"}]}' ;;
          frontend) printf '%s\n' '{"3000/tcp":[{"HostIp":"127.0.0.1","HostPort":"3000"}]}' ;;
          nginx) printf '%s\n' '{"443/tcp":[{"HostIp":"","HostPort":"443"}],"80/tcp":[{"HostIp":"","HostPort":"80"}]}' ;;
          *) printf '{}\n' ;;
        esac
        ;;
      *'{{json .Mounts}}'*)
        case "$service" in
          mongo) printf '%s\n' '[{"Type":"volume","Name":"gole_mongo-data","Destination":"/data/db","RW":true}]' ;;
          redis) printf '%s\n' '[{"Type":"volume","Name":"gole_redis-data","Destination":"/data","RW":true}]' ;;
          minio) printf '%s\n' '[{"Type":"volume","Name":"gole_minio-data","Destination":"/data","RW":true}]' ;;
          *) printf '[]\n' ;;
        esac
        ;;
      *'{{.State.Status}}:{{.State.ExitCode}}'*)
        printf 'initializer-state:%s\n' "$service" >> /tmp/provenance-trace
        printf 'exited:0\n'
        ;;
      *'HostConfig.NanoCpus'*)
        case "$service" in
          mongo) printf '1000000000|1879048192\n' ;;
          mongo-init|mongo-init-1) printf '250000000|268435456\n' ;;
          redis) printf '500000000|402653184\n' ;;
          minio) printf '750000000|805306368\n' ;;
          minio-init|minio-init-1) printf '250000000|134217728\n' ;;
          support-agent) printf '250000000|201326592\n' ;;
          backend) printf '1500000000|2147483648\n' ;;
          budget-relay) printf '250000000|134217728\n' ;;
          frontend) printf '750000000|671088640\n' ;;
          nginx) printf '500000000|201326592\n' ;;
          *) exit 101 ;;
        esac
        ;;
      *'HostConfig.LogConfig.Type'*)
        printf 'logging:%s\n' "$service" >> /tmp/provenance-trace
        printf 'local|10m|3\n'
        ;;
      *'HostConfig.SecurityOpt'*)
        printf 'security:%s\n' "$service" >> /tmp/provenance-trace
        printf '["no-new-privileges:true"]\n'
        ;;
      *'HostConfig.RestartPolicy.Name'*)
        printf 'restart:%s\n' "$service" >> /tmp/provenance-trace
        printf 'unless-stopped\n'
        ;;
      *) printf '{}\n' ;;
    esac
    ;;
  exec) ;;
  *) exit 95 ;;
esac
FAKE_DOCKER

cat > /usr/local/bin/curl <<'FAKE_CURL'
#!/bin/sh
case "$*" in
  *'http://www.gole.co.kr/__gole-canonical-check?source=runtime'*)
    printf '301|https://gole.co.kr/__gole-canonical-check?source=runtime' ;;
  *'https://www.gole.co.kr/__gole-canonical-check?source=runtime'*)
    printf '301|https://gole.co.kr/__gole-canonical-check?source=runtime' ;;
  *'-fsSI '*'https://gole.co.kr/'*)
    printf 'HTTP/2 200\r\nStrict-Transport-Security: max-age=31536000\r\n\r\n' ;;
esac
exit 0
FAKE_CURL

cat > /usr/local/bin/systemctl <<'FAKE_SYSTEMCTL'
#!/bin/sh
if [ "$1" = is-active ] && [ "$2" = --quiet ] &&
  [ "$3" = gole-cost-guard-watchdog.timer ]; then
  exit 0
fi
if [ "$1" = poweroff ]; then
  printf 'poweroff\n' >> /tmp/poweroff-calls
  exit 0
fi
exit 96
FAKE_SYSTEMCTL

cat > /usr/local/bin/sync <<'FAKE_SYNC'
#!/bin/sh
set -eu
state="$(sed -n 's/^state=//p' /etc/gole/gole.env.transaction 2>/dev/null || true)"
printf '%s|state=%s\n' "$*" "$state" >> /tmp/environment-sync-trace
if [ -e /tmp/kill-after-environment-restore-sync ] &&
  [ "$*" = '-f /etc/gole/gole.env' ] && [ "$state" = committed ]; then
  rm -f /tmp/kill-after-environment-restore-sync
  kill -KILL "$PPID"
fi
exit 0
FAKE_SYNC
chmod 0755 /usr/bin/gcloud /usr/local/bin/docker /usr/local/bin/curl \
  /usr/local/bin/systemctl /usr/local/bin/sync

install -m 0600 -o root -g root /fixtures/base.env /etc/gole/gole.env
printf '5\n' > /etc/gole/gole.env.version
printf '%s\n' "$sha" > /etc/gole/deployed.sha
chmod 0644 /etc/gole/gole.env.version /etc/gole/deployed.sha
baseline_hash="$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)"

run_sync() {
  SUDO_USER=root /usr/local/sbin/gole-hostctl secret-sync "$1" "$2"
}

abort_environment_transaction_for_test() {
  SUDO_USER=root /usr/local/sbin/gole-hostctl env-transaction-abort "$1" "$2"
}

assert_restart_trace() {
  expected="$1"
  actual="$(paste -sd, /tmp/restart-trace)"
  if [ "$actual" != "$expected" ]; then
    printf 'unexpected strict LKG restart trace\nexpected: %s\nactual:   %s\n' \
      "$expected" "$actual" >&2
    exit 1
  fi
}

assert_strict_provenance_checks() {
  expected_count="$1"
  for service in mongo redis minio support-agent backend frontend nginx budget-relay; do
    for check in ownership expected-image actual-image networks ports logging security restart; do
      actual_count="$(grep -Fxc "$check:$service" /tmp/provenance-trace || true)"
      service_expected_count="$expected_count"
      # mongo-init intentionally shares the exact mongo image reference, so the
      # expected image lookup is recorded against mongo a second time.
      if [ "$check" = expected-image ] && [ "$service" = mongo ]; then
        service_expected_count=$((expected_count * 2))
      fi
      if [ "$actual_count" -ne "$service_expected_count" ]; then
        printf 'unexpected %s check count for %s: expected %s, got %s\n' \
          "$check" "$service" "$service_expected_count" "$actual_count" >&2
        exit 1
      fi
    done
  done
  for service in mongo-init minio-init; do
    for check in ownership actual-image initializer-state; do
      actual_count="$(grep -Fxc "$check:$service" /tmp/provenance-trace || true)"
      if [ "$actual_count" -ne "$expected_count" ]; then
        printf 'unexpected %s check count for %s: expected %s, got %s\n' \
          "$check" "$service" "$expected_count" "$actual_count" >&2
        exit 1
      fi
    done
  done
  [ "$(grep -Fxc 'expected-image:minio-init' /tmp/provenance-trace || true)" \
    -eq "$expected_count" ] || {
    echo 'unexpected expected-image check count for minio-init' >&2
    exit 1
  }
}

# Replay is rejected before metadata credentials/Secret Manager are touched.
if run_sync 4 00000000-0000-0000-0000-000000000004 >/tmp/lower.out 2>&1; then
  echo 'older Secret Manager version was accepted' >&2
  exit 1
fi
[ ! -e /tmp/gcloud-calls ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = "$baseline_hash" ]

# Same version and bytes is an idempotent no-op.
if ! run_sync 5 00000000-0000-0000-0000-000000000005 >/tmp/equal.out 2>&1; then
  cat /tmp/equal.out >&2
  exit 1
fi
[ ! -e /var/backups/gole-env ]

# A development payload fails before install and never leaks a value/version.
if run_sync 7 00000000-0000-0000-0000-000000000007 >/tmp/development.out 2>&1; then
  echo 'development environment was accepted by Secret Sync' >&2
  exit 1
fi
[ "$(cat /etc/gole/gole.env.version)" = 5 ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = "$baseline_hash" ]
! grep -Fq 'developer@example.test' /tmp/development.out

# validate-production-env.py checks the email latch conditionally now: a
# credential is only accepted together with GOLE_VERIFICATION_EMAIL_ENABLED=
# true. This payload still has the latch off (Stage 0's baseline), so a
# leftover SMTP credential makes it an inconsistent payload that must still
# be rejected before install, and the credential value must never reach the
# retained workflow output either way.
if run_sync 10 00000000-0000-0000-0000-000000000010 >/tmp/smtp.out 2>&1; then
  echo 'inconsistent latch/credential SMTP environment was accepted during Stage 0' >&2
  exit 1
fi
[ "$(cat /etc/gole/gole.env.version)" = 5 ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = "$baseline_hash" ]
! grep -Fq 'legacy-private-app-password' /tmp/smtp.out

v6_hash="$(sha256sum /fixtures/v6.env | cut -d' ' -f1)"
: > /tmp/restart-trace
: > /tmp/provenance-trace
run_sync 6 10000000-0000-0000-0000-000000000006 >/tmp/success.out 2>&1
assert_restart_trace "$v6_hash:support-agent,$v6_hash:backend,$v6_hash:nginx"
assert_strict_provenance_checks 1
[ "$(cat /etc/gole/gole.env.version)" = 6 ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = "$v6_hash" ]

# A failed rollout restores the old env/version, re-verifies the same immutable
# LKG release and leaves no candidate or transaction. The first failed up is
# consumed; recovery must perform a second successful up.
: > /tmp/restart-trace
: > /tmp/provenance-trace
touch /tmp/fail-up-once
if run_sync 8 20000000-0000-0000-0000-000000000008 >/tmp/failure.out 2>&1; then
  echo 'failed environment rollout returned success' >&2
  exit 1
fi
v8_hash="$(sha256sum /fixtures/v8.env | cut -d' ' -f1)"
assert_restart_trace "$v8_hash:support-agent,$v6_hash:support-agent,$v6_hash:backend,$v6_hash:nginx"
assert_strict_provenance_checks 1
[ "$(cat /etc/gole/gole.env.version)" = 6 ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = "$v6_hash" ]
[ ! -e /etc/gole/gole.env.transaction ]
[ ! -e /tmp/poweroff-calls ]

: > /tmp/restart-trace
: > /tmp/provenance-trace
run_sync 8 30000000-0000-0000-0000-000000000008 >/tmp/v8.out 2>&1
assert_restart_trace "$v8_hash:support-agent,$v8_hash:backend,$v8_hash:nginx"
assert_strict_provenance_checks 1

# If the host is killed after the restored environment is fsynced but before
# its version and rollback-restored journal, the next recovery must restore
# those durable markers in that order and remain safely on the old payload.
crash_candidate=/tmp/gole-env.CRASH09
crash_request=40000000-0000-0000-0000-000000000009
install -m 0600 -o root -g root /fixtures/v9.env "$crash_candidate"
SUDO_USER=root /usr/local/sbin/gole-hostctl env-transaction-begin \
  "$crash_candidate" 9 "$crash_request"
SUDO_USER=root /usr/local/sbin/gole-hostctl env-transaction-mark-ready \
  9 "$crash_request"
SUDO_USER=root /usr/local/sbin/gole-hostctl env-transaction-commit \
  9 "$crash_request"
[ "$(cat /etc/gole/gole.env.version)" = 9 ]
touch /tmp/kill-after-environment-restore-sync
if abort_environment_transaction_for_test 9 "$crash_request" \
  >/tmp/restore-kill.out 2>&1; then
  echo 'environment restore survived the injected fsync SIGKILL' >&2
  exit 1
fi
grep -qx 'state=committed' /etc/gole/gole.env.transaction
[ "$(cat /etc/gole/gole.env.version)" = 9 ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = "$v8_hash" ]
: > /tmp/environment-sync-trace
recovery="$(SUDO_USER=root /usr/local/sbin/gole-hostctl env-transaction-recover)"
[ "$recovery" = RECOVERY_REQUIRED ]
env_sync_line="$(grep -nF -- '-f /etc/gole/gole.env|state=committed' \
  /tmp/environment-sync-trace | cut -d: -f1)"
version_sync_line="$(grep -nF -- '-f /etc/gole/gole.env.version|state=committed' \
  /tmp/environment-sync-trace | cut -d: -f1)"
journal_sync_line="$(grep -nF -- '-f /etc/gole/gole.env.transaction|state=rollback-restored' \
  /tmp/environment-sync-trace | cut -d: -f1)"
[ "$env_sync_line" -lt "$version_sync_line" ]
[ "$version_sync_line" -lt "$journal_sync_line" ]
[ "$(cat /etc/gole/gole.env.version)" = 8 ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = "$v8_hash" ]
SUDO_USER=root /usr/local/sbin/gole-hostctl env-transaction-finish-recovery
[ ! -e /etc/gole/gole.env.transaction ]
rm -f -- "$crash_candidate"

: > /tmp/restart-trace
: > /tmp/provenance-trace
run_sync 9 50000000-0000-0000-0000-000000000009 >/tmp/v9.out 2>&1
v9_hash="$(sha256sum /fixtures/v9.env | cut -d' ' -f1)"
assert_restart_trace "$v9_hash:support-agent,$v9_hash:backend,$v9_hash:nginx"
assert_strict_provenance_checks 1
[ "$(cat /etc/gole/gole.env.version)" = 9 ]
[ "$(sha256sum /etc/gole/gole.env | cut -d' ' -f1)" = \
  "$v9_hash" ]

# Plaintext rollback files are root-only and exactly the newest two are kept.
[ "$(find /var/backups/gole-env -maxdepth 1 -type f -name 'gole.env.*' | wc -l)" = 2 ]
while IFS= read -r backup; do
  [ "$(stat -c '%U:%G:%a' "$backup")" = root:root:600 ]
done < <(find /var/backups/gole-env -maxdepth 1 -type f -name 'gole.env.*')
if find /etc/gole -maxdepth 1 -type f \( -name '.secret.*' -o -name '.environment.candidate.*' \) |
  grep -q .; then
  echo 'Secret Sync left a plaintext temporary behind' >&2
  exit 1
fi
for output in /tmp/equal.out /tmp/development.out /tmp/smtp.out /tmp/success.out /tmp/failure.out /tmp/v8.out /tmp/v9.out; do
  ! grep -Fq 'legacy-private-app-password' "$output"
  ! grep -Eq '[0-9a-f]{64}' "$output"
done

echo 'Secret Sync root broker, rollback, replay, and backup retention tests passed.'
CONTAINER_TEST
