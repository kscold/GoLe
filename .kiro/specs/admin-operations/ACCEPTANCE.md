# 감독 후속 검증 — OWNER B

Task: task_a13dc1f7554a / Dispatch: ctx_08823cd734e4

## 현재 출시 정책

사용자의 현재 정책은 **커뮤니티 + 매물/채팅 직거래**, 플랫폼 결제/자동 정산 미개방이다. 실제 Mongo에 저장된 운영 단계/override는 이 작업에서 읽거나 변경하지 않았다. 소스의 LaunchStage.PREPARING 및 BROWSE_ONLY는 이 정책을 표현하며, PAYMENTS는 TRADING, PARTNER_PAYOUT은 FULL 이후 기본 활성이다. 운영 자동화는 단계/feature override를 수정하는 usecase를 전혀 주입하지 않으며 점검 결과 READY가 결제/정산 활성화를 일으키지 않는다.

## 수용 기준과 판정

| 기준 | 구현/검증 | 판정 |
| --- | --- | --- |
| 기존 기능 조사 후 중복 방지 | 기존 대사/지급/파이프라인/배송/Discord outbox 조사 및 HANDOFF 표 | 충족 |
| 안전한 기존 usecase만 실행 | 예외큐 read, 결제 설정 read, Discord 활성 설정 read만 allowlist | 충족 |
| ADMIN | 기존 /api/admin/** guard; 무세션/일반 역할 거부 및 actor 요구 테스트 | 충족 |
| 임의 shell/URL/고위험 실행 차단 | 고정 job ID 검사, 임의 URL/알 수 없는 job 테스트 | 충족 |
| 중복 실행 | Mongo `_id` 원자 선점, 충돌 거부, run 소유권 기반 해제 테스트 | 충족(실 DB 경쟁 테스트 별도) |
| audit | 시작 저장 선행, 완료/실패 결과 저장, audit 실패 시 실행 차단·잠금 유지 테스트 | 충족 |
| 실패/재시도 | 고정 실패 코드, retryOf+같은 job+FAILED 검증, UI 명시 확인 | 충족 |
| 비밀 마스킹 | exception 문자열 저장 안 함; webhook 노출 안 함; Sentry event allowlist 투영 | 충족 |
| Sentry 환경/중복/rate 정책 | opt-in, production error/fatal, 5분 dedupe, 분당 3건 로컬 budget 테스트 | 충족(준비 helper) |
| 결제/정산 자동 개방 금지 | launch 변경 포트/외부 실행 포트 미사용 | 충족 |
| 웹 검증 | 타입/lint + Orca 별도 탭 로컬 응답 모킹, 실제 권한 실패 시 fail closed | 아래 검증 기록 참조 |

## BLOCKED — 외부 Sentry/Discord 설정

주 세션 확인: 현재 Sentry 로그인 대상은 **pawpong-mq 타 조직**이며 GoLe 대상 조직/프로젝트/Discord 채널은 미확정이다. 이 조직을 GoLe 조직으로 간주하거나 프로젝트를 임의로 고르지 않는다. 실제 설치/alert 생성/SDK DSN 연결/테스트 메시지 발송은 blocked이며 주 세션이 대상을 확정해야 한다. 로컬 필터/마스킹/준비 상태는 완료한다. 필요한 값과 정확한 클릭 절차는 HANDOFF.md에 있다.

## 남은 구현 gap (이번 구현이 완료됐다고 주장하지 않는 범위)

1. 기존 자동 배치의 예약 실행 이력/health 상세/스케줄 변경은 미연결. 현재 원장은 신규 명시적 진단만 수집한다. 안전한 공개 포트와 각 스케줄러 소유자의 계측 변경 필요.
2. Sentry SDK 패키지 및 web/server instrumentation 미설치. manifest/build.gradle/공통 설정은 수정하지 않았다. Sentry 준비 helper는 자동 수집기가 아니다.
3. 기존 API 오류 Discord 발행과 Sentry API alert의 중복 경로 정리 및 cross-instance rate policy는 외부/공통 소유자 작업.
4. Mongo 잠금은 프로세스 중단 시 fail closed로 남는다. 운영자가 해당 run 미실행 확인 후 원장/잠금을 함께 복구해야 한다. 자동 unlock/TTL은 없다.
5. 실제 API8090 프로세스는 재시작하지 않았다. 신규 엔드포인트의 live E2E 및 실제 Mongo 경쟁 검증은 통합 담당자 몫이다.
6. 별도 operations 감사 원장은 공통 admin audit에 아직 합쳐지지 않았다(공통 enum/배럴 수정 금지 준수).

## 최종 검증 결과

- operations JUnit **12 tests, 0 failures, 0 errors** (권한 1, service/audit/retry 8, 기존 diagnostic 연결 1, Mongo 잠금 소유권 2).
- core/web typecheck 및 소유 TSX eslint 통과. Sentry policy local 필터/마스킹/dedupe/expiry/rate budget 테스트 통과. git diff --check 통과.
- Orca 별도 페이지의 API fetch를 로컬 모킹: 미점검 화면 → 점검 실행 → 확인 → FAILED 원장/고정 원인 표시 → 재시도 확인 → SUCCEEDED/원본 retryOf 연결 및 예외 2건 표시까지 통과. 외부 설정 차단 문구도 확인.
- `browser-mocked.png`는 이 **모킹 데이터**의 실제 렌더링 스크린샷이다. 실제 backend/Discord 전달 성공 증거가 아니다.
- 소유 Java 파일만 개별 absolute path로 Spotless 포맷 적용. 전체 공통 파일에는 format 적용하지 않음.
