#!/usr/bin/env bash
set -Eeuo pipefail

# Initial package is pinned and verified. GitHub's service may self-update it after
# registration so security fixes are not blocked by the image bootstrap cadence.
RUNNER_VERSION="2.337.0"
RUNNER_SHA256_X64="70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613"
# Keep the dedicated account's runner separate from the one-time legacy
# human-account installation at /opt/actions-runner. The legacy directory is
# retained as a root-inaccessible forensic artifact after its unit is retired;
# sharing the path would either trust its credentials or make registration
# fail before GitHub's same-name --replace can run.
RUNNER_ROOT="/opt/gole-actions-runner"
RUNNER_SERVICE="gole-github-runner.service"
RUNNER_SERVICE_FILE="/etc/systemd/system/$RUNNER_SERVICE"
LEGACY_RUNNER_SERVICE="actions.runner.GoLe-by-Colding-GoLe.gole-gcp-production.service"
LEGACY_RUNNER_SERVICE_FILE="/etc/systemd/system/$LEGACY_RUNNER_SERVICE"
LEGACY_RUNNER_RETIRED_UNIT="/etc/gole/legacy-runner.service.retired"
LEGACY_RUNNER_ROOT="/opt/actions-runner"
LEGACY_RUNNER_UNIT_SHA256="06b078f9895218dbf279b2f5abbd18db6acd024e26572461a445130708fd4349"
DEPLOY_IDENTITY_FILE="/etc/gole/deploy-user"
RUNNER_BOOTSTRAP_FILE="/etc/gole/github-runner-bootstrap.conf"
RUNNER_REGISTRATION_FILE="/etc/gole/github-runner-registration.conf"

die() {
  echo "$*" >&2
  exit 1
}

usage() {
  cat >&2 <<'EOF'
Usage: sudo register-github-runner.sh --token-stdin

Reads one short-lived GitHub Actions runner registration token from stdin.
The token is never written to disk and must not be passed as a command argument.
EOF
  exit 2
}

if [ "$(id -u)" -ne 0 ]; then
  die "run as root"
fi
if [ "${1:-}" != "--token-stdin" ] || [ "$#" -ne 1 ]; then
  usage
fi
if [ "$(uname -m)" != "x86_64" ]; then
  die "this deployment runner must be Linux X64"
fi
if [ ! -r "$DEPLOY_IDENTITY_FILE" ] || [ ! -r "$RUNNER_BOOTSTRAP_FILE" ]; then
  die "run bootstrap-host.sh before registering the runner"
fi

IFS=: read -r DEPLOY_USER DEPLOY_GROUP < "$DEPLOY_IDENTITY_FILE"
REPOSITORY_URL=""
RUNNER_NAME=""
RUNNER_LABELS=""
while IFS='=' read -r key value || [ -n "${key}${value}" ]; do
  case "$key" in
    repository_url) REPOSITORY_URL="$value" ;;
    runner_name) RUNNER_NAME="$value" ;;
    runner_labels) RUNNER_LABELS="$value" ;;
    *) die "invalid runner bootstrap configuration" ;;
  esac
done < "$RUNNER_BOOTSTRAP_FILE"

[[ "$DEPLOY_USER" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || die "invalid deploy user"
[[ "$DEPLOY_GROUP" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || die "invalid deploy group"
[[ "$REPOSITORY_URL" =~ ^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.git$ ]] ||
  die "invalid repository URL"
[[ "$RUNNER_NAME" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "invalid runner name"
[[ "$RUNNER_LABELS" =~ ^[A-Za-z0-9._-]+(,[A-Za-z0-9._-]+)*$ ]] || die "invalid runner labels"
case ",$RUNNER_LABELS," in
  *,gole-gcp-production,*) ;;
  *) die "runner labels must include gole-gcp-production" ;;
esac
id "$DEPLOY_USER" >/dev/null 2>&1 || die "deploy user does not exist"
[ "$(id -gn "$DEPLOY_USER")" = "$DEPLOY_GROUP" ] || die "deploy group does not match"
if [ "$DEPLOY_USER" != "goledeploy" ] ||
  [ "$(id -nG "$DEPLOY_USER" | tr ' ' '\n' | sort -u | tr '\n' ' ')" != "$DEPLOY_GROUP " ]; then
  die "runner account must be the isolated goledeploy user with no supplemental groups"
fi

ensure_runner_service() {
  local unit_candidate
  if [ ! -f "$RUNNER_ROOT/runsvc.sh" ] || [ -L "$RUNNER_ROOT/runsvc.sh" ] ||
    [ ! -x "$RUNNER_ROOT/runsvc.sh" ]; then
    die "runner service command is missing or invalid"
  fi
  unit_candidate="$(mktemp)"
  cat > "$unit_candidate" <<EOF
[Unit]
Description=GoLe repository-scoped GitHub Actions runner
After=network-online.target gole-cloud-broker.service
Wants=network-online.target
Requires=gole-cloud-broker.service
ConditionPathIsExecutable=/usr/local/libexec/gole/runner-start-allowed.sh

[Service]
User=$DEPLOY_USER
Group=$DEPLOY_GROUP
WorkingDirectory=$RUNNER_ROOT
ExecCondition=/usr/local/libexec/gole/runner-start-allowed.sh
ExecStart=$RUNNER_ROOT/runsvc.sh
KillMode=control-group
KillSignal=SIGINT
TimeoutStopSec=5min
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
  install -m 0644 -o root -g root "$unit_candidate" "$RUNNER_SERVICE_FILE"
  rm -f -- "$unit_candidate"
  printf '%s\n' "$RUNNER_SERVICE" > "$RUNNER_ROOT/.service"
  chown "$DEPLOY_USER:$DEPLOY_GROUP" "$RUNNER_ROOT/.service"
  chmod 0644 "$RUNNER_ROOT/.service"
  systemctl daemon-reload
  systemctl enable --now "$RUNNER_SERVICE"
  systemctl is-active --quiet "$RUNNER_SERVICE"
}

legacy_runner_root_state() {
  if [ ! -e "$LEGACY_RUNNER_ROOT" ] && [ ! -L "$LEGACY_RUNNER_ROOT" ]; then
    printf 'absent\n'
    return
  fi
  [ -d "$LEGACY_RUNNER_ROOT" ] && [ ! -L "$LEGACY_RUNNER_ROOT" ] &&
    ! mountpoint -q "$LEGACY_RUNNER_ROOT" || die "legacy runner root is invalid"
  case "$(stat -c '%U:%G:%a' "$LEGACY_RUNNER_ROOT")" in
    kscold:kscold:755)
      [ -f "$LEGACY_RUNNER_ROOT/.runner" ] && [ ! -L "$LEGACY_RUNNER_ROOT/.runner" ] &&
        [ "$(stat -c '%U:%G:%a:%h' "$LEGACY_RUNNER_ROOT/.runner")" = \
          kscold:kscold:664:1 ] || die "legacy runner registration metadata is invalid"
      [ -f "$LEGACY_RUNNER_ROOT/runsvc.sh" ] && [ ! -L "$LEGACY_RUNNER_ROOT/runsvc.sh" ] &&
        [ -x "$LEGACY_RUNNER_ROOT/runsvc.sh" ] &&
        [ "$(stat -c '%U:%G:%a:%h' "$LEGACY_RUNNER_ROOT/runsvc.sh")" = \
          kscold:kscold:755:1 ] || die "legacy runner service command metadata is invalid"
      printf 'unsealed\n'
      ;;
    root:root:700)
      [ -f "$LEGACY_RUNNER_ROOT/.runner" ] && [ ! -L "$LEGACY_RUNNER_ROOT/.runner" ] ||
        die "sealed legacy runner registration is invalid"
      # Linux symlink mode is always 0777; the sealed root directory controls
      # traversal. Still require root ownership of links and do not follow them.
      if find "$LEGACY_RUNNER_ROOT" -xdev \
        \( ! -user root -o ! -group root -o \( ! -type l -a -perm /0077 \) \) \
        -print -quit | grep -q .; then
        die "sealed legacy runner root is readable or not root-owned"
      fi
      printf 'sealed\n'
      ;;
    *) die "legacy runner root ownership or permissions are invalid" ;;
  esac
}

verify_legacy_runner_cgroup_empty() {
  local cgroup control_group
  control_group="$(systemctl show --property=ControlGroup --value \
    "$LEGACY_RUNNER_SERVICE")" || die "legacy runner control group cannot be read"
  [ -n "$control_group" ] || return 0
  [[ "$control_group" =~ ^/[A-Za-z0-9_.@:/-]+$ ]] &&
    [[ "$control_group" != *..* ]] && [ "$control_group" != / ] ||
    die "legacy runner control group is invalid"
  cgroup="/sys/fs/cgroup${control_group}"
  [ ! -e "$cgroup" ] && return 0
  [ -d "$cgroup" ] && [ ! -L "$cgroup" ] && [ -r "$cgroup/cgroup.procs" ] ||
    die "legacy runner control group cannot be verified"
  [ -z "$(cat "$cgroup/cgroup.procs")" ] ||
    die "legacy runner child processes remain"
}

validate_retired_legacy_runner_unit() {
  local metadata
  [ -f "$LEGACY_RUNNER_RETIRED_UNIT" ] && [ ! -L "$LEGACY_RUNNER_RETIRED_UNIT" ] &&
    [ "$(sha256sum "$LEGACY_RUNNER_RETIRED_UNIT" | cut -d' ' -f1)" = \
      "$LEGACY_RUNNER_UNIT_SHA256" ] || die "retired legacy runner unit is invalid"
  metadata="$(stat -c '%U:%G:%a:%h' "$LEGACY_RUNNER_RETIRED_UNIT")"
  case "$metadata" in
    root:root:600:1) ;;
    # An interruption immediately after the same-filesystem rename may retain
    # the reviewed unit's old 0664 mode. Only its exact hash/identity reaches
    # this normalization path; no foreign retirement artifact is adopted.
    root:root:664:1)
      chmod 0600 "$LEGACY_RUNNER_RETIRED_UNIT"
      sync -f "$LEGACY_RUNNER_RETIRED_UNIT"
      sync -f /etc/gole
      ;;
    *) die "retired legacy runner unit metadata is invalid" ;;
  esac
  [ "$(stat -c '%U:%G:%a:%h' "$LEGACY_RUNNER_RETIRED_UNIT")" = root:root:600:1 ] ||
    die "retired legacy runner unit could not be sealed"
}

retire_nonstandard_runner_services() {
  local active_state group root_state unit_state
  local -a legacy_units=()
  shopt -s nullglob
  legacy_units=(/etc/systemd/system/actions.runner.*.service)
  shopt -u nullglob
  [ "${#legacy_units[@]}" -le 1 ] || die "unexpected legacy runner services remain"

  if [ -e "$LEGACY_RUNNER_RETIRED_UNIT" ] || [ -L "$LEGACY_RUNNER_RETIRED_UNIT" ]; then
    validate_retired_legacy_runner_unit
  fi

  root_state="$(legacy_runner_root_state)"
  if [ "${#legacy_units[@]}" -eq 0 ]; then
    if [ "$root_state" = unsealed ] &&
      [ ! -e "$LEGACY_RUNNER_RETIRED_UNIT" ] &&
      [ ! -L "$LEGACY_RUNNER_RETIRED_UNIT" ]; then
      die "unsealed legacy runner root has no reviewed retired unit"
    fi
    return
  fi
  [ "${legacy_units[0]}" = "$LEGACY_RUNNER_SERVICE_FILE" ] ||
    die "unexpected legacy runner service remains"
  [ "$root_state" = unsealed ] || die "legacy runner service does not match its root"
  [ -f "$LEGACY_RUNNER_SERVICE_FILE" ] && [ ! -L "$LEGACY_RUNNER_SERVICE_FILE" ] &&
    [ "$(stat -c '%U:%G:%a:%h' "$LEGACY_RUNNER_SERVICE_FILE")" = root:root:664:1 ] &&
    [ "$(sha256sum "$LEGACY_RUNNER_SERVICE_FILE" | cut -d' ' -f1)" = \
      "$LEGACY_RUNNER_UNIT_SHA256" ] || die "legacy runner service file is not the reviewed live unit"
  grep -Fqx 'ExecStart=/opt/actions-runner/runsvc.sh' "$LEGACY_RUNNER_SERVICE_FILE" &&
    grep -Fqx 'User=kscold' "$LEGACY_RUNNER_SERVICE_FILE" &&
    grep -Fqx 'WorkingDirectory=/opt/actions-runner' "$LEGACY_RUNNER_SERVICE_FILE" ||
    die "legacy runner service fields changed"
  [ "$(systemctl show --property=User --value "$LEGACY_RUNNER_SERVICE")" = kscold ] ||
    die "legacy runner service user changed"
  group="$(systemctl show --property=Group --value "$LEGACY_RUNNER_SERVICE")"
  [ -z "$group" ] || die "legacy runner service group changed"
  [ "$(systemctl show --property=WorkingDirectory --value "$LEGACY_RUNNER_SERVICE")" = \
    "$LEGACY_RUNNER_ROOT" ] || die "legacy runner working directory changed"
  active_state="$(systemctl show --property=ActiveState --value "$LEGACY_RUNNER_SERVICE")"
  unit_state="$(systemctl show --property=UnitFileState --value "$LEGACY_RUNNER_SERVICE")"
  [ "$active_state" = inactive ] && [ "$unit_state" = disabled ] ||
    die "legacy runner service is not quiescent"
  verify_legacy_runner_cgroup_empty
  systemctl disable --now "$LEGACY_RUNNER_SERVICE"
  [ "$(systemctl show --property=ActiveState --value "$LEGACY_RUNNER_SERVICE")" = inactive ] &&
    [ "$(systemctl show --property=UnitFileState --value "$LEGACY_RUNNER_SERVICE")" = disabled ] ||
    die "legacy runner service did not remain quiescent"
  verify_legacy_runner_cgroup_empty
  [ ! -e "$LEGACY_RUNNER_RETIRED_UNIT" ] && [ ! -L "$LEGACY_RUNNER_RETIRED_UNIT" ] ||
    die "retired legacy runner unit already exists"
  mv -- "$LEGACY_RUNNER_SERVICE_FILE" "$LEGACY_RUNNER_RETIRED_UNIT"
  chown root:root "$LEGACY_RUNNER_RETIRED_UNIT"
  chmod 0600 "$LEGACY_RUNNER_RETIRED_UNIT"
  sync -f "$LEGACY_RUNNER_RETIRED_UNIT"
  sync -f /etc/gole
  sync -f /etc/systemd/system
  validate_retired_legacy_runner_unit
  [ ! -e "$LEGACY_RUNNER_SERVICE_FILE" ] && [ ! -L "$LEGACY_RUNNER_SERVICE_FILE" ] ||
    die "legacy runner service was not retired"
  systemctl daemon-reload
}

seal_legacy_runner_root() {
  local root_state
  root_state="$(legacy_runner_root_state)"
  case "$root_state" in
    absent|sealed) return ;;
    unsealed) ;;
    *) die "legacy runner root state is invalid" ;;
  esac
  chown -R root:root -- "$LEGACY_RUNNER_ROOT"
  chmod -R go-rwx -- "$LEGACY_RUNNER_ROOT"
  chmod 0700 "$LEGACY_RUNNER_ROOT"
  sync -f "$LEGACY_RUNNER_ROOT"
  [ "$(legacy_runner_root_state)" = sealed ] ||
    die "legacy runner root could not be sealed"
}

runner_registration_marker_is_valid() {
  local initial_version="" key marker_labels="" marker_name="" marker_repository="" value
  local seen_initial=false seen_labels=false seen_name=false seen_repository=false
  [ -f "$RUNNER_REGISTRATION_FILE" ] && [ ! -L "$RUNNER_REGISTRATION_FILE" ] &&
    [ "$(stat -c '%U:%G:%a:%h' "$RUNNER_REGISTRATION_FILE")" = root:root:644:1 ] ||
    return 1
  while IFS='=' read -r key value || [ -n "${key}${value}" ]; do
    case "$key" in
      repository_url)
        [ "$seen_repository" = false ] || return 1
        seen_repository=true
        marker_repository="$value"
        ;;
      runner_name)
        [ "$seen_name" = false ] || return 1
        seen_name=true
        marker_name="$value"
        ;;
      runner_labels)
        [ "$seen_labels" = false ] || return 1
        seen_labels=true
        marker_labels="$value"
        ;;
      initial_runner_version)
        [ "$seen_initial" = false ] || return 1
        seen_initial=true
        initial_version="$value"
        ;;
      *) return 1 ;;
    esac
  done < "$RUNNER_REGISTRATION_FILE"
  [ "$marker_repository" = "$REPOSITORY_URL" ] &&
    [ "$marker_name" = "$RUNNER_NAME" ] &&
    [ "$marker_labels" = "$RUNNER_LABELS" ] &&
    [ "$initial_version" = "$RUNNER_VERSION" ]
}

if [ -f "$RUNNER_ROOT/.runner" ]; then
  runner_registration_marker_is_valid ||
    die "configured runner registration marker is missing or invalid"
  retire_nonstandard_runner_services
  chown -R "$DEPLOY_USER:$DEPLOY_GROUP" "$RUNNER_ROOT"
  chmod 0750 "$RUNNER_ROOT"
  seal_legacy_runner_root
  ensure_runner_service >/dev/null
  echo "GitHub Actions runner is already configured; ensured its service is running."
  exit 0
fi

RUNNER_TOKEN=""
if ! IFS= read -r RUNNER_TOKEN && [ -z "$RUNNER_TOKEN" ]; then
  die "no registration token was provided on stdin"
fi
if [[ ! "$RUNNER_TOKEN" =~ ^[A-Za-z0-9._-]{20,512}$ ]]; then
  die "registration token format is invalid"
fi
if IFS= read -r _extra_token_input; then
  die "stdin must contain exactly one registration token"
fi

download_dir=""
registration_candidate=""
runner_root_created=false
registration_succeeded=false
cleanup_registration_attempt() {
  local exit_status=$?
  RUNNER_TOKEN=""
  if [ -n "$download_dir" ]; then
    rm -rf -- "$download_dir" || true
  fi
  if [ -n "$registration_candidate" ] && [ -f "$registration_candidate" ] &&
    [ ! -L "$registration_candidate" ]; then
    rm -f -- "$registration_candidate" || true
  fi
  if [ "$registration_succeeded" != true ] &&
    runner_registration_marker_is_valid; then
    registration_succeeded=true
  fi
  if [ "$runner_root_created" = true ] && [ "$registration_succeeded" != true ]; then
    if [ "$RUNNER_ROOT" = /opt/gole-actions-runner ] &&
      [ -d "$RUNNER_ROOT" ] && [ ! -L "$RUNNER_ROOT" ] &&
      ! mountpoint -q "$RUNNER_ROOT"; then
      # This exact path was created by this invocation from the pinned archive.
      # A failed config must not block a retry with a fresh short-lived token.
      rm -rf --one-file-system -- "$RUNNER_ROOT" ||
        echo "failed to clean the unregistered dedicated runner root" >&2
    else
      echo "refusing to clean an unexpected dedicated runner root" >&2
    fi
  fi
  return "$exit_status"
}
trap cleanup_registration_attempt EXIT

download_dir="$(mktemp -d)"
archive="$download_dir/actions-runner-linux-x64.tar.gz"
curl --fail --location --proto '=https' --retry 3 --show-error --silent \
  "https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz" \
  --output "$archive"
printf '%s  %s\n' "$RUNNER_SHA256_X64" "$archive" | sha256sum --check --status ||
  die "GitHub Actions runner checksum mismatch"

if [ -e "$RUNNER_ROOT" ] || [ -L "$RUNNER_ROOT" ]; then
  die "unconfigured runner root already exists; inspect it before retrying"
fi
install -d -m 0750 -o root -g root "$RUNNER_ROOT"
runner_root_created=true
tar --extract --gzip --file "$archive" --directory "$RUNNER_ROOT" --no-same-owner
"$RUNNER_ROOT/bin/installdependencies.sh"
chown -R "$DEPLOY_USER:$DEPLOY_GROUP" "$RUNNER_ROOT"
chmod 0750 "$RUNNER_ROOT"

# An older human-account runner must be stopped and its systemd unit removed
# before the same repository/name is replaced. Its directory and credentials
# are left untouched for forensic recovery; --replace invalidates the old
# registration at GitHub.
retire_nonstandard_runner_services

(
  # Runner.Listener treats ACTIONS_RUNNER_INPUT_TOKEN as a secret, masks it and
  # removes it from its environment immediately after parsing. Unlike --token,
  # the value is never exposed in the process command line. runuser resets the
  # root environment and whitelists only this one masked input.
  export ACTIONS_RUNNER_INPUT_TOKEN="$RUNNER_TOKEN"
  runuser -u "$DEPLOY_USER" --whitelist-environment=ACTIONS_RUNNER_INPUT_TOKEN -- \
    "$RUNNER_ROOT/config.sh" \
    --unattended \
    --replace \
    --url "${REPOSITORY_URL%.git}" \
    --name "$RUNNER_NAME" \
    --labels "$RUNNER_LABELS" \
    --work _work
)
[ -f "$RUNNER_ROOT/.runner" ] && [ ! -L "$RUNNER_ROOT/.runner" ] ||
  die "runner registration did not create valid local metadata"
RUNNER_TOKEN=""

[ ! -e "$RUNNER_REGISTRATION_FILE" ] && [ ! -L "$RUNNER_REGISTRATION_FILE" ] ||
  die "runner registration marker already exists unexpectedly"
registration_candidate="$(mktemp /etc/gole/.github-runner-registration.XXXXXX)"
cat > "$registration_candidate" <<EOF
repository_url=$REPOSITORY_URL
runner_name=$RUNNER_NAME
runner_labels=$RUNNER_LABELS
initial_runner_version=$RUNNER_VERSION
EOF
chown root:root "$registration_candidate"
chmod 0644 "$registration_candidate"
sync -f "$registration_candidate"
mv -- "$registration_candidate" "$RUNNER_REGISTRATION_FILE"
registration_candidate=""
sync -f /etc/gole
runner_registration_marker_is_valid ||
  die "runner registration marker could not be committed"
registration_succeeded=true

seal_legacy_runner_root
ensure_runner_service
echo "GitHub Actions runner registered without persisting the short-lived registration token."
