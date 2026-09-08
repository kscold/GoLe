# 관리자 고도화 통합 기준 — 2026-09-08

## 기준선과 출처

통합 브랜치는 `dev`, 시작점은 `main`의 `5623c110`이다. 기존 `origin/dev`
(`3a45c00a`)는 그 조상이므로 73개 커밋을 포함한 최신 main에서 출발한다.
세 Codex 작업은 같은 checkout의 미커밋 구현으로 들어오며 별도 기능 브랜치가 아니다.
후속 검증은 Orca Run `run_4852df707c4c`에서 추적한다.

| 출처 | 비교 결과 | 통합 판단 |
| --- | --- | --- |
| `origin/feat/footer-and-content-actions` | 공통 출시·운영 기획은 main과 동일. 이 브랜치에는 최신 모바일 스펙·운영 문서가 없고 OAuth 스펙이 이전 계약이다 | 최신 main 유지, 과거 snapshot으로 덮어쓰지 않음 |
| `origin/fix/backup-minio-network` | 백업 장애 운영 문서는 main과 동일 | 중복 복사 없음 |
| `origin/fix/profile-nickname-and-oauth-recovery` | 소셜 로그인 스펙은 main과 동일 | 중복 복사 없음 |
| 그 외 로컬/원격 작업 브랜치 | `.kiro`·`docs`의 merge-base 이후 기획 변경 없음(Dependabot 제외) | 구현 브랜치 전체 병합은 기획 통합과 구분 |
| `GoLe-obsidian/main` | 2026-08-29 제품 설명과 WBS. 현재 코드보다 오래됨 | 볼트에 현재 구현 기준과 정본 링크 반영 |
| 디자인·운영·배송 세션 | 현재 dev의 신규 수직 기능 | 각 소유자 검증 보고 및 통합 검사 후 수용 |

브랜치 이력이 다르더라도 문서 내용이 동일하면 미반영 기획으로 세지 않는다.
소스 정본은 기존 `.kiro/specs/<domain>/`, 이 문서는 통합 판단과 수용 기준만 관리한다.

## 현재 제품 단계 해석

- 목표는 커뮤니티·카탈로그·시세 근거·기존 대화 중심의 공개 경험이다.
- Stage 숫자는 운영 DB 상태다. 코드를 만들었거나 dev에서 페이지가 200이라고 운영 단계를 올리지 않는다.
- `LaunchStage`, `LaunchConfig`, `LaunchGateInterceptor`, 공유 `SAFE_LAUNCH_CONFIG`를 함께 확인한다.
- Stage 0/1은 `DIRECT_CHAT`, Stage 2는 `MANUAL_SETTLEMENT`, Stage 3은 `PARTNER_PAYOUT`이다.
- 신규 판매/거래 진입은 단계 외 판매자 신원확인 래치와 계정별 조건을 별도로 만족해야 한다.
- 이메일 인증 준비 상태도 별도이다. Stage 0라는 이유로 가입·인증이 가능하다고 단정하지 않는다.
- 서버 미설정은 Stage 1, 클라이언트 설정 조회 실패는 Stage 0 안전 기본값이다.
- 실제 운영 stage와 준비 승인은 이번 통합에서 조회·변경하지 않았다.

정본: [출시 단계](../launch-stage/spec.md), [인증](../social-login/spec.md),
[시세 근거](../pricing-evidence/spec.md), [배송](../shipping-and-fees/tasks.md).

## 고도화 작업과 완료 정의

| 기능 | 사용자 가치 | 수용 조건 | 운영 활성화 조건 |
| --- | --- | --- | --- |
| 디자인 토큰 | 관리자에서 미리보기·초안 저장·게시·복원 | 허용 토큰/값 검증, ADMIN, revision 충돌, 영속 이력, 공개 fallback, 실제 테마 적용 | 검증한 토큰 revision을 운영자가 명시적으로 게시 |
| 운영 자동화 | 실행 가능한 점검·실패·재시도·이력을 한 화면에 | 고정 작업 목록, 기존 usecase 연결, 권한, 중복 실행 방지, 감사 실패 처리 | 점검 성공과 결제/연동 준비 완료를 구분 |
| 배송 Tracker | 설정 준비상태·연결검증·샘플 조회·안전 재조회 | 공식 adapter 재사용, 비밀 미노출, UNKNOWN, cache/timeout/rate-limit, ADMIN | Tracker 자격증명과 실제 권한 있는 송장 검증 |
| Sentry → Discord | 웹 오류의 환경별 중복 억제 알림 | 기존 API Discord 알림과 소유 경계, 민감정보 최소화, 실패/제한 검증 | GoLe 조직·프로젝트·Discord 대상 확정 후 SDK와 알림 규칙 연결 |

UI가 존재하는 것, mock 테스트 통과, 실행 중인 API에서 검증한 것, 운영 연결 완료는 서로 다른 상태다.
결제·환불·지급·삭제 자동 실행을 이 범위의 점검 버튼으로 열지 않는다.

## 후속 우선순위

1. P0: 신규 API의 서버 인가와 실제 API/DB 통합 검증. 테스트용 mock을 운영 성공 근거로 사용하지 않는다.
2. P1: 전용 디자인/운영/배송 감사 이력을 중앙 감사 화면에서 조회할 수 있게 read projection 통합.
3. P1: 운영 작업 잠금 복구 절차와 다중 인스턴스 Tracker budget을 검증 가능한 형태로 보강.
4. P1: Sentry 대상 조직·프로젝트·채널 확정 및 SDK/실 전달 검증. 현재 `pawpong-mq` 로그인은 GoLe 대상 확정 근거가 아니다.
5. P2: 기존 launch 운영 승인과 별개로 범용 WBS 보드가 필요한지 기획. 현재 범용 WBS 편집기가 구현됐다고 표시하지 않는다.

## 검증 기록

각 담당 보고서는 `admin-design`, `admin-operations`, `admin-tracker` 스펙 폴더에 둔다.
전체 검증 결과와 미실행 항목은 이 파일의 후속 기록으로 남긴다. 운영 배포·푸시는 별도다.

### dev 통합 검사

- 2026-09-08 coordinator: web/core typecheck, web 전체 ESLint, 디자인 core 검증,
  Sentry 정책 검증, design/operations/shipping 선택 Gradle 테스트 통과.
- 실제 API 8090 연결과 Tracker 실송장/Sentry 외부 전달은 이 검사에 포함되지 않는다.
- 현재 세 작업자는 별도 브랜치가 아니라 공유 dev checkout에서 작업했다.
- 과거 `kscold/pricing-data-integrity`의 3개 커밋은 아직 dev에 없다.
  현재 주문/시세 코드와 달라 별도 포팅·회귀 검증 대상으로 남기며 전체 수용으로 표시하지 않는다.
- 앱 버전 정책, FCM 운영 관리, 배너 제작/예약 게시 관리는 추가 요청이며 아직 미구현이다.
- 신규 Sentry 조직은 별도 Orca Task `task_a34266ec8ea6`으로 진행하며 기존 Pawpong 조직은 변경하지 않는다.
