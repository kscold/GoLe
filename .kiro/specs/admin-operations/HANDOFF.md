# OWNER B — 운영 자동화 / Sentry → Discord 준비

2026-09-08. 소유 범위에서만 신규 파일 추가. commit/push/install/server restart/외부 알림 발송 없음.

## 구현

- `/admin/operations`: 명시적 실행 확인 + 사유 코드, 현재 점검 결과, 마지막 실행, 실패 원인, 최근 100건 감사 이력, 실패 실행을 지정하는 재시도.
- `GET /api/admin/operations`, `POST /api/admin/operations/{jobId}/runs`.
- 기존 `/api/admin/**` AdminAuthInterceptor + 세션/CSRF 관례 사용. 클라이언트는 `@gole/core/operations` subpath; 공통 배럴 변경 없음.
- 고정 registry 3개: `exception-queue` → 기존 ExceptionQueueService.list, `payment-readiness` → GetPaymentReadinessUseCase, `alert-readiness` → 기존 DiscordOperationsProperties 상태 확인. 읽기 전용이며 외부 HTTP/알림 발행 포트를 호출하지 않음.
- SUCCEEDED는 **진단 실행 완료**. PAYMENT_DISABLED/MISCONFIGURED와 SENTRY_NOT_INSTRUMENTED는 정상 서비스/연결 성공을 뜻하지 않음. 화면에 구분함.
- `operations_runs`에 실행자 account ID, 사유 코드, retryOf, 시작/종료/결과를 저장. 자유 입력/이메일/예외 문자열/credential 저장 없음. 공통 감사 enum/barrel 수정 제한으로 별도 감사 원장 사용.
- `operations_locks`의 job ID `_id` 유니크 삽입으로 인스턴스 간 실행 중복 차단. runId 일치 소유자만 해제. 감사 시작 저장 전 실행 금지, 완료 저장 실패 시 잠금 유지. DB 장애를 성공으로 바꾸지 않음.
- 프로세스 중단 시 잠금 자동 만료 없음(fail closed). 해당 runId가 실제 실행 중이지 않음을 운영자가 확인하고, 미완료 이력 및 잠금을 함께 복구하는 운영 절차가 필요함. 무조건 시간 만료/자동 잠금 제거는 넣지 않음. 동일 실패 건을 시간차로 다시 실행하는 것은 허용되며 모두 별도 감사 기록임.
- 목록의 마지막 실행은 최근 100건 범위이며, 기존 스케줄러 실행 이력은 수집하지 않음. 페이지에서 이 제한을 명시.

## 기존 기능 조사 / 중복 방지

| 기존 코드 | 확인한 동작 | 이 화면의 처리 |
| --- | --- | --- |
| PaymentReconciliationScheduler | PortOne 검증, 결제 상태 전이, 환불 대사 | 수동 일괄 실행 금지. 주문 전용 화면으로 연결 |
| ProviderSettlementScheduler | 지급 원장 선점 및 실제 외부 지급 | 실행 registry 제외 |
| OrderPipelineScheduler | 시간 규칙에 따른 주문 상태 변경/알림 | 실행 registry 제외 |
| ShipmentTrackingScheduler | 기존 배송 조회·상태 반영 | OWNER C 배송 연동 화면 소유, 중복 구현 안 함 |
| ExceptionQueueService | 현재 주문/배송 예외 계산 | 읽기 전용 재집계 연결 |
| SupportNotificationOutboxWorker/AdminService | durable 발송, 재시도/backoff/dead-letter, 확인문구 기반 재큐잉 | 이미 구현됨. 중복 worker/발송 버튼 없음 |
| DiscordOperationalEventPublisher | 기존 가입/결제/문의/운영 이벤트 발행, APPLICATION dedupe, in-flight 차단, 재시도 | 그대로 유지, 새 Discord sender 없음 |
| DiscordConfigurationGuard | enabled일 때 역할별 webhook 검증 | 비밀 URL 노출 없이 enabled 상태만 반환 |
| GlobalExceptionHandler/ApplicationLifecycleNotifier | 기존 API 오류/기동 이벤트 Discord 알림 | Sentry 적용 시 동일 API 오류 이중 알림을 피할 소유 경계 필요 |
| Sentry | 기존 src/manifest에서 SDK/instrumentation 설정 발견 안 됨 | SDK 미연결을 명시, 준비 정책만 구현 |

## Sentry 로컬 준비 정책

`packages/core/src/operations/sentry-policy.ts` 및 `@gole/core/operations`의 `createSentryPolicy`:

- 기본 비활성. 명시적 enabled + production + error/fatal만 통과.
- 원본 event spread 없이 허용 component(web/api/operations), diagnostic(UNEXPECTED_ERROR/DEPENDENCY_UNAVAILABLE/JOB_FAILED), level, environment, fingerprint만 새 객체에 투영.
- user/request/header/cookie/URL/query/exception text/stack locals/breadcrumb/extra/임의 tags 전부 제거. 진단력보다 비밀 미노출을 우선한 보수적 준비 정책. 임의 event title도 Discord로 보내지 않음.
- component+diagnostic 당 5분 dedupe, 프로세스/브라우저 인스턴스 당 분당 3건 제한. 네트워크 전달 확인을 의미하지 않음. cross-instance 전체 제한은 Sentry alert rule에서도 설정할 것.
- SDK beforeSend 연결용 **준비 helper**이며 현재 자동 오류 수집/외부 전송은 없음. 스택을 제거하므로 같은 component/diagnostic 오류는 묶임. 세밀한 grouping이 필요하면 검토한 오류 코드 allowlist를 확장하고 raw exception을 되살리지 말 것.

## 주 세션이 완료할 외부/공통 변경 — 정확한 절차

아래 값은 아직 미확정. 임의 선택하거나 기존 credential 출력하지 않았음.

1. 사용자가 로그인한 Sentry에서 **조직 slug, 대상 web/API 프로젝트, 운영 environment 명칭**, Discord **서버와 채널 ID**를 확정. SDK DSN은 해당 프로젝트에서 얻고 secret manager/deployment env에 입력. Sentry auth token(소스맵 업로드용)은 CI secret 전용; NEXT_PUBLIC에 넣지 않음. Discord webhook token을 Sentry 브라우저 폼에 붙일 필요 없음(공식 bot 통합 사용).
2. Sentry `Settings > Integrations > Discord`에서 기존 설치부터 확인. 동일 서버 설치가 있으면 재사용. 없으면 `Add Installation` → 사용자가 확정한 서버 → `Continue` → 권한 검토 → `Authorize`. Sentry owner/manager/admin 및 Discord Manage Server 권한 필요.
3. `Alerts > Create Alert` → 오류 alert → `Set Conditions`. environment production, error/fatal, 신규/재발 이슈 조건, action interval 5분(이슈별) 설정. Discord action에서 확정된 서버 및 **채널 ID 또는 URL** 입력(채널 이름 아님). notification tags는 검토된 component/diagnostic/environment만.
4. 기존 동일 프로젝트/environment/Discord 대상 규칙이 있는지 조사하여 새 규칙을 중복 만들지 않음. 기존 API 오류 Discord 이벤트와 Sentry API 오류 중 한 경로를 운영 알림의 원본으로 결정. 초기에는 web 오류만 Sentry 대상으로 분리하면 기존 API 경로와 중복 없음. 실제 API 전환에는 common 오류 발행기 소유자의 변경 필요.
5. 공식 문서의 `Send Test Notification` 단계는 **수행하지 않았음**. 실제 채널로 테스트 발송은 사용자 별도 허용과 대상 확정 후 주 세션이 담당. 검토 전 alert 활성화/테스트 버튼/이벤트 생성 금지.
6. SDK 설치는 manifest 수정 소유자 작업: web에 `@sentry/nextjs`를 현재 Next 16.3.3 호환 버전으로 선정·추가. web instrumentation-client.ts / instrumentation.ts / server·edge config / next.config 연결이 필요. 정책 helper를 각 런타임의 beforeSend에 연결하되 `sendDefaultPii: false`, replay/tracing/profiling 기본 비활성, local/test DSN 비활성. 설치 전 실제 SDK 타입으로 연결 검증은 불가능하므로 설치 담당자가 typecheck 및 SDK transport mock 검증 수행.
7. API는 Spring Boot **4.0.6** 호환 Sentry SDK artifact/version을 공식 compatibility 기준으로 별도 선정(Boot 3 starter를 추정 추가하지 않음). 현재 build.gradle.kts 수정 권한 없음. 운영 설정 key를 추가해도 실제 SDK bean/transport 검증 전 READY로 표시하지 말 것.
8. 기존 스케줄러들의 마지막 실행·실패/건수 집계를 통합하려면 해당 소유자가 `operations` inbound 기록 포트를 추가/호출하는 후속 변경 필요. 지금 진단 이력을 스케줄러의 실 실행 이력으로 재사용하지 말 것. 공통 `/admin/audit` 통합은 새 action/target enum과 read projection 협의 필요.
9. 플랫폼 공통 health 상세를 공개하지 말 것. 필요시 ADMIN 전용 health port를 추가하고 UP/DOWN 및 검토된 dependency 이름만 반환; actuator 전체 detail/설정값을 프런트로 전달하지 말 것.

공식 근거(2026-09-08 조회):
- https://docs.sentry.io/organization/integrations/notification-incidents/discord/ — 공식 bot 설치, 권한, alert action, channel ID/URL.
- https://docs.sentry.io/platforms/javascript/guides/nextjs/configuration/filtering/ — beforeSend 필터/수정/null drop.
- https://docs.sentry.io/api/monitors/create-an-alert-for-an-organization/ — 환경 조건, action frequency, Discord action shape. 조직 API를 호출하지 않았음.

## 검증 명령

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home apps/api/gradlew -p apps/api test --tests 'com.gole.api.operations.*'
pnpm --filter @gole/core typecheck
pnpm --filter web typecheck
pnpm --filter web exec eslint src/views/admin-operations/index.tsx 'src/app/(main)/admin/operations/page.tsx'
node .kiro/specs/admin-operations/sentry-policy.test.mjs
```

브라우저: 원래 web3010 서버를 그대로 사용. 실제 권한 확인은 API 연결 오류로 fail-closed UI 확인. 별도 Orca 탭에만 fetch mock을 주입해 명시적 실행 → 실패 사유/이력 → 재시도 흐름 검증. 실제 세션 값/쿠키 출력이나 변경 없음. 실제 새 API는 서버 재시작하지 않아 live E2E 검증하지 못함. Mongo 원자 잠금은 MongoTemplate 포트 단위 검증이며 실제 Mongo 경쟁 통합테스트는 미실행.
