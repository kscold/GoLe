#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
IMAGE="ubuntu@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517"

docker run --rm --interactive \
  --volume "$ROOT:/source:ro" \
  "$IMAGE" bash -seu <<'CONTAINER_TEST'
groupadd goledeploy
useradd --system --create-home --home-dir /home/goledeploy \
  --shell /bin/bash --gid goledeploy goledeploy
groupadd kscold
useradd --system --no-create-home --home-dir /home/kscold \
  --shell /bin/bash --gid kscold kscold
install -d -m 0755 /etc/gole /etc/systemd/system /usr/local/libexec/gole /test-bin
install -d -m 0750 -o goledeploy -g goledeploy /opt/gole-actions-runner
install -d -m 0755 /opt/actions-runner
printf 'goledeploy:goledeploy\n' > /etc/gole/deploy-user
install -m 0755 /source/infra/gcp/scripts/runner-start-allowed.sh \
  /usr/local/libexec/gole/runner-start-allowed.sh
cat > /etc/gole/github-runner-bootstrap.conf <<'EOF'
repository_url=https://github.com/GoLe-by-Colding/GoLe.git
runner_name=gole-gcp-production
runner_labels=gole-gcp-production
EOF
cat > /etc/gole/github-runner-registration.conf <<'EOF'
repository_url=https://github.com/GoLe-by-Colding/GoLe.git
runner_name=gole-gcp-production
runner_labels=gole-gcp-production
initial_runner_version=2.337.0
EOF

standard_service='gole-github-runner.service'
legacy_service='actions.runner.GoLe-by-Colding-GoLe.gole-gcp-production.service'
touch /opt/gole-actions-runner/.runner
printf '%s\n' "$legacy_service" > /opt/actions-runner/.service
cat > "/etc/systemd/system/$legacy_service" <<'EOF'
[Unit]
Description=GitHub Actions Runner (GoLe-by-Colding-GoLe.gole-gcp-production)
After=network-online.target

[Service]
ExecStart=/opt/actions-runner/runsvc.sh
User=kscold
WorkingDirectory=/opt/actions-runner
KillMode=process
KillSignal=SIGTERM
TimeoutStopSec=5min

[Install]
WantedBy=multi-user.target
EOF
chmod 0664 "/etc/systemd/system/$legacy_service"

cat > /test-bin/systemctl <<'EOF'
#!/bin/sh
set -eu
if [ "$1" = show ]; then
  property=''
  for argument in "$@"; do
    case "$argument" in --property=*) property="${argument#--property=}" ;; esac
  done
  case "$property" in
    User) printf 'kscold\n' ;;
    Group) printf '\n' ;;
    WorkingDirectory) printf '/opt/actions-runner\n' ;;
    ActiveState)
      if [ "${FAKE_LEGACY_ACTIVE:-0}" = 1 ]; then printf 'active\n'; else printf 'inactive\n'; fi ;;
    UnitFileState) printf 'disabled\n' ;;
    ControlGroup)
      if [ "${FAKE_LEGACY_BAD_CGROUP:-0}" = 1 ]; then printf '/\n'; else printf '\n'; fi ;;
    *) exit 93 ;;
  esac
  exit 0
fi
exit 0
EOF
cat > /test-bin/uname <<'EOF'
#!/bin/sh
[ "${1:-}" = -m ] && { echo x86_64; exit 0; }
exec /usr/bin/uname "$@"
EOF

touch /opt/gole-actions-runner/runsvc.sh /opt/actions-runner/runsvc.sh \
  /opt/actions-runner/.runner
chmod 0755 /opt/gole-actions-runner/runsvc.sh /opt/actions-runner/runsvc.sh \
  /test-bin/systemctl
chmod 0755 /test-bin/uname
chown -R kscold:kscold /opt/actions-runner
chmod 0755 /opt/actions-runner /opt/actions-runner/runsvc.sh
chmod 0664 /opt/actions-runner/.runner
export PATH="/test-bin:$PATH"

cp /etc/gole/github-runner-registration.conf /tmp/runner-registration.valid
sed -i '1irunner_name=' /etc/gole/github-runner-registration.conf
if bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin \
  </dev/null >/tmp/duplicate-marker.out 2>&1; then
  echo 'registration accepted a duplicate marker field' >&2
  exit 1
fi
grep -q 'registration marker is missing or invalid' /tmp/duplicate-marker.out
cp /tmp/runner-registration.valid /etc/gole/github-runner-registration.conf

# An extra or modified actions.runner unit is never swept up by a broad glob.
touch /etc/systemd/system/actions.runner.unexpected.service
if bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin \
  </dev/null >/tmp/unexpected-unit.out 2>&1; then
  echo 'registration retired an unexpected legacy runner unit' >&2
  exit 1
fi
grep -q 'unexpected legacy runner services remain' /tmp/unexpected-unit.out
rm -f /etc/systemd/system/actions.runner.unexpected.service

cp "/etc/systemd/system/$legacy_service" /tmp/legacy-runner.service
printf 'ExecStartPre=/bin/true\n' >> "/etc/systemd/system/$legacy_service"
if bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin \
  </dev/null >/tmp/modified-unit.out 2>&1; then
  echo 'registration retired a modified legacy runner unit' >&2
  exit 1
fi
grep -q 'not the reviewed live unit' /tmp/modified-unit.out
cp /tmp/legacy-runner.service "/etc/systemd/system/$legacy_service"
chmod 0664 "/etc/systemd/system/$legacy_service"

if FAKE_LEGACY_ACTIVE=1 bash /source/infra/gcp/scripts/register-github-runner.sh \
  --token-stdin </dev/null >/tmp/active-unit.out 2>&1; then
  echo 'registration retired an active legacy runner unit' >&2
  exit 1
fi
grep -q 'legacy runner service is not quiescent' /tmp/active-unit.out
if FAKE_LEGACY_BAD_CGROUP=1 bash /source/infra/gcp/scripts/register-github-runner.sh \
  --token-stdin </dev/null >/tmp/cgroup-unit.out 2>&1; then
  echo 'registration accepted an invalid legacy runner cgroup' >&2
  exit 1
fi
grep -q 'legacy runner control group is invalid' /tmp/cgroup-unit.out

# Unit disappearance alone is not retirement evidence for an unsealed
# human-account runner directory.
mv "/etc/systemd/system/$legacy_service" /tmp/legacy-runner.service.missing
if bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin \
  </dev/null >/tmp/missing-evidence.out 2>&1; then
  echo 'registration accepted an unsealed legacy root without retirement evidence' >&2
  exit 1
fi
grep -q 'unsealed legacy runner root has no reviewed retired unit' \
  /tmp/missing-evidence.out
mv /tmp/legacy-runner.service.missing "/etc/systemd/system/$legacy_service"

ln -s runsvc.sh /opt/actions-runner/npm
bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin </dev/null
chown -h kscold:kscold /opt/actions-runner/npm
if bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin </dev/null >/dev/null 2>&1; then
  echo 'sealed runner accepted a non-root-owned symlink' >&2
  exit 1
fi
chown -h root:root /opt/actions-runner/npm

[ ! -e "/etc/systemd/system/$legacy_service" ]
[ -d /opt/actions-runner ]
[ -f /opt/actions-runner/.runner ]
[ -f /opt/actions-runner/.service ]
[ "$(stat -c '%U:%G:%a' /opt/actions-runner)" = root:root:700 ]
if find /opt/actions-runner -xdev \
  \( ! -user root -o ! -group root -o \( ! -type l -a -perm /0077 \) \) -print -quit | grep -q .; then
  echo 'legacy runner still has accessible files or untrusted ownership' >&2
  exit 1
fi
[ "$(stat -c '%U:%G:%a:%h' /etc/gole/legacy-runner.service.retired)" = \
  root:root:600:1 ]
[ "$(sha256sum /etc/gole/legacy-runner.service.retired | cut -d' ' -f1)" = \
  06b078f9895218dbf279b2f5abbd18db6acd024e26572461a445130708fd4349 ]
[ "$(cat /opt/gole-actions-runner/.service)" = "$standard_service" ]
grep -qx 'User=goledeploy' "/etc/systemd/system/$standard_service"
grep -qx 'Requires=gole-cloud-broker.service' "/etc/systemd/system/$standard_service"
grep -qx 'ExecCondition=/usr/local/libexec/gole/runner-start-allowed.sh' \
  "/etc/systemd/system/$standard_service"
[ "$(find /etc/systemd/system -maxdepth 1 -name 'actions.runner.*.service' | wc -l)" -eq 0 ]
grep -qx 'ExecStart=/opt/gole-actions-runner/runsvc.sh' "/etc/systemd/system/$standard_service"
grep -qx 'KillMode=control-group' "/etc/systemd/system/$standard_service"
[ "$(stat -c '%U:%G:%a' "/etc/systemd/system/$standard_service")" = 'root:root:644' ]

# The sealed forensic root and retired exact unit make registration retries
# idempotent. Normalize only the atomic-rename 0664 crash window back to 0600.
chmod 0664 /etc/gole/legacy-runner.service.retired
bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin </dev/null
[ "$(stat -c '%U:%G:%a:%h' /etc/gole/legacy-runner.service.retired)" = \
  root:root:600:1 ]
cp /etc/gole/legacy-runner.service.retired /tmp/retired-unit.valid
printf 'foreign=true\n' >> /etc/gole/legacy-runner.service.retired
if bash /source/infra/gcp/scripts/register-github-runner.sh --token-stdin \
  </dev/null >/tmp/foreign-retired.out 2>&1; then
  echo 'registration accepted a modified retired runner unit' >&2
  exit 1
fi
grep -q 'retired legacy runner unit is invalid' /tmp/foreign-retired.out
cp /tmp/retired-unit.valid /etc/gole/legacy-runner.service.retired
chmod 0600 /etc/gole/legacy-runner.service.retired

# Pending mode is the only temporary state allowed to start the first-CD
# runner. Ratcheting and malformed state both fail closed across reboot.
/usr/local/libexec/gole/runner-start-allowed.sh

# An active first-deployment journal without an LKG marker blocks the runner.
printf 'opaque-root-transaction\n' > /etc/gole/deployment.transaction
chmod 0600 /etc/gole/deployment.transaction
if /usr/local/libexec/gole/runner-start-allowed.sh; then
  echo 'runner start gate accepted a failed initial deployment transaction' >&2
  exit 1
fi
printf '%040d\n' 1 > /etc/gole/deployed.sha
chmod 0644 /etc/gole/deployed.sha
/usr/local/libexec/gole/runner-start-allowed.sh
chmod 0644 /etc/gole/deployment.transaction
if /usr/local/libexec/gole/runner-start-allowed.sh; then
  echo 'runner start gate accepted a deployment transaction with unsafe metadata' >&2
  exit 1
fi
rm -f /etc/gole/deployment.transaction /etc/gole/deployed.sha

printf 'state=pending\nlegacy_sha=%040d\n' 0 > /etc/gole/metadata-migration.pending
chmod 0644 /etc/gole/metadata-migration.pending
/usr/local/libexec/gole/runner-start-allowed.sh
sed -i 's/state=pending/state=ratcheting/' /etc/gole/metadata-migration.pending
if /usr/local/libexec/gole/runner-start-allowed.sh; then
  echo 'runner start gate accepted ratcheting metadata state' >&2
  exit 1
fi
printf 'state=pending\nlegacy_sha=invalid\n' > /etc/gole/metadata-migration.pending
if /usr/local/libexec/gole/runner-start-allowed.sh; then
  echo 'runner start gate accepted malformed metadata state' >&2
  exit 1
fi
printf 'state=pending\nlegacy_sha=%040d\n\n' 0 > /etc/gole/metadata-migration.pending
if /usr/local/libexec/gole/runner-start-allowed.sh; then
  echo 'runner start gate accepted an extra blank marker line' >&2
  exit 1
fi
printf 'state=pending\nlegacy_sha=%040d\nunknown=value\n' 0 > /etc/gole/metadata-migration.pending
if /usr/local/libexec/gole/runner-start-allowed.sh; then
  echo 'runner start gate accepted an unknown marker field' >&2
  exit 1
fi
printf 'state=pending\nlegacy_sha=%040d\nunknown=value' 0 > /etc/gole/metadata-migration.pending
if /usr/local/libexec/gole/runner-start-allowed.sh; then
  echo 'runner start gate ignored a final unterminated marker field' >&2
  exit 1
fi

echo 'Dedicated runner service migration runtime test passed.'
CONTAINER_TEST
