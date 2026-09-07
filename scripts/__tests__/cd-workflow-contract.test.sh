#!/usr/bin/env bash
#
# cd.yml 운영 알림 계약 테스트 — 워크플로를 실행하지 않고 정적으로만 검증한다.
#
#   실행:  bash scripts/__tests__/cd-workflow-contract.test.sh
#
# 검증하는 계약:
#   1. 배포 step이 DISCORD_SUPPRESS_NOTIFICATIONS를 명시적으로 주입한다.
#      (누락되면 application.yml 기본값 true가 이겨 ERROR 경보까지 무음이 된다)
#   2. 배포 후 readiness 실패 알림은 deploy.sh가 이미 알린 경우와 겹치지 않는다.
#      (배포 실패 알림은 어느 경로로든 실행당 정확히 한 건)
#   3. webhook URL은 secrets 참조로만 등장한다 — 리터럴 URL이 커밋되면 실패시킨다.
#   4. 첫 rollout도 CI 통과 SHA의 새 deploy.sh를 실행하며 직전 SHA를 rollback에 넘긴다.
#   5. 앱 배포 실패만으로 VM을 끄지 않고 비용 가드 둘 다 없을 때만 정지한다.
#   6. 과거 CI 재실행이나 빌드 중 뒤처진 SHA가 최신 main을 덮지 못한다.
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/cd.yml"
DEPLOY_SCRIPT="$REPO_ROOT/scripts/deploy.sh"

python3 - "$WORKFLOW" "$DEPLOY_SCRIPT" <<'PY'
import re
import sys

raw = open(sys.argv[1], encoding="utf-8").read()
deploy = open(sys.argv[2], encoding="utf-8").read()
# Only the live (non-comment) YAML lines matter for the email-latch check
# below — validator/compose comments that document the conditional latch
# design are allowed to mention "true" without tripping it.
raw_live = "\n".join(
    line for line in raw.splitlines() if not line.strip().startswith("#")
)
failures = []


def check(label, ok, detail=""):
    if ok:
        print(f"  ok   {label}")
    else:
        print(f"  FAIL {label}{' — ' + detail if detail else ''}")
        failures.append(label)


check("배포 step에 id: deploy 가 있다", "id: deploy" in raw)
check(
    "host 이관 완료 전 CD job이 생성되지 않는다",
    "vars.GOLE_PRODUCTION_HOST_READY == 'true'" in raw,
)
check(
    "Discord overlay를 rollout lock 전에 root helper에 stdin으로 설치한다",
    "Install root-owned Discord routing overlay" in raw
    and 'gole-hostctl discord-overlay-install' in raw
    and 'DISCORD_SUPPORT_WEBHOOK_URL:-$operations' in raw
    and 'DISCORD_DEPLOY_WEBHOOK_URL:-$operations' in raw
    and deploy.index("install_discord_overlay") < deploy.index('exec 7>>"$ROLLOUT_LOCK"'),
)
check(
    "parent rollout lock 경로는 overlay를 재설치하지 않고 root 검증만 한다",
    "discord-overlay-verify" in deploy
    and 'GOLE_ROLLOUT_LOCK_HELD:-0' in deploy,
)
check(
    "배포 step이 무음 기본값 false를 명시한다",
    re.search(r"DISCORD_SUPPRESS_NOTIFICATIONS:\s*\$\{\{[^\n]*'false'", raw) is not None,
)
check("배포 후 readiness 실패 알림 step이 있다", "Notify post-deploy readiness failure" in raw)
check(
    "readiness 알림이 deploy 성공 케이스로 좁혀져 있다",
    "steps.deploy.outcome == 'success'" in raw,
)
check("readiness 알림이 failure()에서만 돈다", "failure() && steps.deploy.outcome" in raw)
check(
    "webhook 리터럴 URL이 없다",
    "discord.com/api/webhooks" not in raw,
    "webhook URL은 secrets 로만 주입해야 한다",
)
check(
    "PortOne·GA/GTM build 값은 GitHub vars가 아니라 root Secret에서 온다",
    "vars.NEXT_PUBLIC_PORTONE" not in raw
    and "vars.NEXT_PUBLIC_GA" not in raw
    and "vars.NEXT_PUBLIC_GTM" not in raw,
)
check(
    "Stage 0 CD는 이메일 발송과 mail health를 명시적으로 비활성화한다",
    re.search(r'GOLE_VERIFICATION_EMAIL_ENABLED:\s*["\']false["\']', raw_live) is not None
    and re.search(r'GOLE_MAIL_HEALTH_ENABLED:\s*["\']false["\']', raw_live) is not None
    and re.search(r'GOLE_VERIFICATION_EMAIL_ENABLED:\s*["\']true["\']', raw_live) is None
    and re.search(r'GOLE_MAIL_HEALTH_ENABLED:\s*["\']true["\']', raw_live) is None,
)
check(
    "CD는 비밀 payload 대신 exact Secret version만 root helper에 전달한다",
    "GOLE_PRODUCTION_ENV_SECRET_VERSION: ${{ vars.GOLE_PRODUCTION_ENV_SECRET_VERSION }}" in raw
    and "deployment-environment-prepare" in deploy
    and deploy.index("deployment-images-snapshot")
    < deploy.index("deployment-environment-prepare")
    < deploy.index('log "Docker Compose build"'),
)
check(
    "첫 rollout이 root 소유 LKG SHA를 보존한다",
    re.search(
        r'previous_sha="\$\(sudo -n /usr/local/sbin/gole-hostctl '
        r'deployment-read-sha(?: 2>/dev/null)?\)"',
        raw,
    )
    is not None,
)
check(
    "첫 rollout이 검증 SHA의 새 deploy.sh에 rollback SHA를 전달한다",
    'ROLLBACK_SHA="$previous_sha" DEPLOY_SHA="$DEPLOY_SHA"' in raw
    and "bash /app/scripts/deploy.sh all" in raw,
)
check(
    "VM 정지는 watchdog 실패 경로로만 제한한다",
    "failure() && steps.watchdog.outcome == 'failure'" in raw,
)
check(
    "정상 all 배포가 성공 확정 전에 LKG SHA를 root marker에 기록한다",
    'deployment-record-sha "$deployed_sha"' in deploy
    and deploy.index('deployment-record-sha "$deployed_sha"')
    < deploy.index("DEPLOY_MUTATED=0", deploy.index('deployment-record-sha "$deployed_sha"')),
)
check(
    "기존 host 또는 container 비용 가드가 살면 VM을 유지한다",
    "gole-hostctl cost-guard-fail-closed" in raw
    and "docker inspect" not in raw,
)
check(
    "GitHub 현재 main ref와 배포 후보 SHA를 API에서 대조한다",
    "/git/ref/heads/main" in raw
    and 'current_main != sha' in raw
    and 'CANDIDATE_SHA:' in raw,
)
check(
    "운영 checkout 직전에 origin/main과 후보 SHA를 다시 대조한다",
    'git rev-parse refs/remotes/origin/main' in raw
    and '!= "$DEPLOY_SHA"' in raw,
)
check(
    "마지막 정상 배포의 후속 커밋만 자동 배포한다",
    'git merge-base --is-ancestor "$previous_sha" "$DEPLOY_SHA"' in raw,
)
check(
    "긴 이미지 빌드 뒤 live 변경 전에 main 전진을 다시 검사한다",
    "빌드 중 main이 갱신되어 뒤처진 배포 후보를 폐기합니다." in deploy
    and deploy.index("빌드 중 main이 갱신되어 뒤처진 배포 후보를 폐기합니다.")
    < deploy.index('log "Docker Compose rolling update"'),
)

sys.exit(1 if failures else 0)
PY

printf '✔ cd.yml 운영 알림 계약 테스트 통과\n'
