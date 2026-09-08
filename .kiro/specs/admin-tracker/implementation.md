# 기존 배송 Tracker 관리자 연동 — OWNER C

2026-09-08. shipping 헥사 구조와 ShipmentService 생성자/공개 시그니처를 유지했다. 소유 범위 밖 파일, 공통 barrel/security/application.yml/manifests는 수정하지 않았다. commit/push, 설치, 서버 재시작, 실제 Tracker 요청 및 외부 알림 발송은 하지 않았다.

## 구현 계약

- 페이지: `/admin/integrations/tracker`. 기존 관리자 셸 안에 렌더링. 공통 nav 수정은 하지 않았으며 다른 소유자가 추가한 배송 연동 링크를 확인했다.
- 공유 클라이언트: `@gole/core/tracker-admin` (기존 wildcard subpath export). 공통 core barrel 수정 불필요.
- `GET /api/admin/integrations/tracker`: enabled/configured/connected, lastSuccessAt/lastFailureAt/lastFailure. credential 값/일부 문자열도 반환하지 않는다. configured는 실 어댑터가 사용할 자격증명 준비 여부이며 비활성 스텁에서는 false.
- `POST .../verify`: 택배사 목록 GraphQL 쿼리로 인증 확인. 60초 내 재검증은 진단 결과를 재사용.
- `POST .../sample`: `{carrier, waybillNumber}`. 기존 Carrier allowlist·WaybillNumber 불변식 사용. 숫자 정규화 후 8~20자리, HTTP 입력 최대 40자.
- `POST .../requery`: `{orderId}`. ID는 `[a-zA-Z0-9_-]{1,100}`. 저장된 배송의 택배사/송장을 읽고 캐시를 경유해 조회. **배송/주문 저장, 배송완료 알림, 자동확정·정산을 일으키지 않는다.** 결과는 관찰값이며 주문에 강제 반영하는 액션이 아니다.
- 샘플 응답은 마지막 4자리만 노출. `live`는 실 어댑터 경로인지 의미하며 UNKNOWN은 실제 배송 사실을 뜻하지 않는다. `checkedAt`은 요청 시각이고 provider 이벤트 시각이 아니다. `cached`는 공유 Redis 캐시 사용 여부이며 어댑터 내부 60초 중복 방지도 별도로 적용된다.
- 기존 `/api/admin/**` AdminAuthInterceptor + 세션 관례 사용. 미인증 401/일반회원 403. 서비스에서도 조치자 ID 누락을 방어한다. 미연결 시 샘플/검증이 스텁을 호출하지 않는다.

## 안전장치와 기록

- 기존 DeliveryTrackerApiAdapter만 확장. 목적지는 정확히 `https://apis.tracker.delivery/graphql`로 고정 검증한다. 다른 SHIPPING_TRACKER_API_BASE 값은 실 어댑터 시작 시 거부. 리다이렉트 추적 금지. 요청/연결 timeout 기본 5초, 허용 최대 15초.
- HTTP 오류뿐 아니라 HTTP 200의 GraphQL errors/부분 오류를 UNKNOWN 처리. `track:null`/잘못된 JSON/알 수 없는 상태 역시 UNKNOWN. 유효한 track의 명시적 lastEvent:null만 PENDING. 기존 상태명 rawStatus는 유지하되 관리자 응답에는 넣지 않는다.
- 예외 메시지·provider 원문·Authorization·송장번호를 어댑터 로그에 쓰지 않는다. 진단은 정해진 실패 코드만 보관한다.
- 프로세스 내 동시 track 요청은 직렬화, carrier/waybill당 60초 중복 방지 캐시(최대 1,000개). 모든 실 요청을 합쳐 프로세스당 분당 최대 100회. HTTP/GraphQL 실패는 추가 요청 60초 차단. provider 429도 처리한다.
- ShipmentService의 기존 track 호출을 프로세스 내 직렬화해 스케줄러/당사자 새로고침 경합을 줄인다. 기존 Redis 캐시 유지. UNKNOWN 캐시는 60초로 단축해 10분간 장애 상태가 남는 것을 방지한다.
- 관리자 비용성 요청은 모든 관리자 합계 프로세스당 5초 간격(429 + Retry-After). 성공 상태 캐시 10분, UNKNOWN 60초. 강제 cache bypass API 없음.
- 감사는 shipping 소유 `TrackerAdminAuditPort` → Mongo `shipping_tracker_admin_actions`. actorId/action/targetId/occurredAt만 저장. 샘플 targetId는 carrier key, 재조회는 orderId. `VERIFY_CONNECTION`, `SAMPLE_LOOKUP`, `SHIPMENT_REQUERY` 시도 기록을 **외부 요청보다 먼저** 저장하며 감사 DB 장애면 호출을 차단한다. credential/송장/임의 사용자 사유 저장 없음.
- 진단·쿨다운·동시호출 잠금은 프로세스 메모리이며 재시작 시 초기화된다. 다중 API 인스턴스 전체를 묶는 distributed lock/budget은 아니다. Redis 결과 캐시는 공유되지만 cache miss 순간 인스턴스 간 중복은 가능하므로 확장 운영 전 아래 후속을 적용해야 한다.

## 공통 소유자 인수인계 (이 작업에서 수정하지 않음)

1. 중앙 감사 화면 통합이 필요하면 AdminActionType에 위 세 종류를 추가하고 AdminTargetType에 INTEGRATION/SHIPMENT 의미를 명확히 정의한 뒤, 기존 감사 인바운드 포트로 어댑터를 연결한다. 현재 전용 컬렉션에 감사는 실제 구현되어 있으나 `/admin/audit`에는 자동 노출되지 않는다. 전용 기록의 보존·관리 정책도 중앙 정책에 합류시킬 것.
2. 다중 인스턴스 배포에서는 Redis lease/원자 budget을 Tracker outbound port 뒤에 추가하고, scheduler 중복 실행/알림 발송의 DB 멱등 경계도 함께 검토한다. 현재 한도는 인스턴스 수만큼 증가한다.
3. 다른 SHIPPING_TRACKER_API_BASE를 사용하던 환경은 공식 고정 URL로 돌려야 한다. 공통 application.yml 기본값은 이미 일치한다.

## 실연동에 남은 정확한 단계

1. 운영자가 Delivery Tracker Console에서 프로젝트와 Client ID/Client Secret을 발급한다. 이 작업에서는 발급/조회/수집하지 않았다.
2. **API 실행 프로세스의 secret 환경변수**에 `SHIPPING_TRACKER_CLIENT_ID`, `SHIPPING_TRACKER_CLIENT_SECRET`을 주입하고 `SHIPPING_TRACKER_ENABLED=true`를 설정한다. `apps/api/src/main/resources/application.yml:328` 이하가 이미 이 변수를 연결한다. 웹 NEXT_PUBLIC 변수 또는 DB에 저장하면 안 된다.
3. 로컬 `scripts/sync-dev-env.sh`는 외부 Tracker 자격증명을 제외하고 SHIPPING_TRACKER_ENABLED=false로 생성한다. 따라서 동기화만으로 실연결되지 않으며, 명시적으로 준비한 API 프로세스 환경에 주입해야 한다. 이번 작업은 이 스크립트/환경파일을 실행·수정하지 않았다.
4. 설정을 준비한 뒤 API 재배포/재기동을 별도로 수행한다. 현재 web3010/api8090은 재시작하지 않았으므로 새 Java 코드가 실행 중이라는 보장은 없다.
5. ADMIN 로그인 → 위 페이지 → 상태 새로고침 → enabled/configured 확인 → 연결 검증 → 본인에게 조회 권한이 있는 실제 송장으로 샘플 조회. 실제 provider 사용량이 발생할 수 있다. 이후 기존 주문 안전 재조회로 확인한다.
6. 실송장 전체 E2E(F4)는 이 단계 이후 남아 있다. 스텁이나 모킹 검증을 실연동 성공으로 간주하지 않는다.

## 공식 계약 확인

2026-09-08 공식 문서를 확인했다.

- https://tracker.delivery/docs/authentication — 고정 GraphQL endpoint, `Authorization: TRACKQL-API-KEY <clientId>:<clientSecret>`, 인증 확인용 carriers 쿼리. HTTP 상태만으로 GraphQL 성공을 판단하지 않는다.
- https://tracker.delivery/en/docs/client-libraries/nodejs-graphql-request — track(carrierId: ID!, trackingNumber: String!), lastEvent.status.code 계약. 기존 쿼리와 인증 헤더는 일치하여 교체하지 않았다.

## 검증 결과

- `./apps/api/gradlew -p apps/api test --tests 'com.gole.api.shipping.*'`: 45 tests, 0 failures/errors (기존 도메인/ShipmentService 포함).
- 신규 adapter 20개: 전체 상태 매핑, missing credential, HTTP 인증 실패, HTTP 200 GraphQL 부분 실패, null track/lastEvent 구분, timeout, malformed body, concurrent single-flight, 60초 캐시 만료, 분당 100회 한도, provider 429 cooldown, 고정 URL/인증 헤더/timeout 검증.
- 신규 admin service 7개: 미연결 스텁 차단, credential 누락, 캐시 hit/마스킹/감사, UNKNOWN TTL/429, 감사 장애 fail-closed, 입력/조치자 검증, 재조회 시 저장 불변.
- 신규 controller 4개: 기존 AdminAuthInterceptor를 연결한 MockMvc로 401/403/ADMIN 응답 및 JSON 입력 validation 확인. 진단 응답에 clientId/clientSecret 없음.
- `pnpm --filter @gole/core typecheck`, `pnpm --filter web typecheck` 통과. 최초 웹 검사 시 다른 세션 공통 layout 편집 중 구문 오류가 있었으나 재실행은 통과.
- 소유 웹 경로 ESLint/Prettier 검사 통과, 전체 웹 `fsd:lint` 통과. 소유 Java 11파일만 절대경로 spotlessIdeHook으로 포맷했다. diff whitespace 검사 통과.
- Orca 별도 페이지 `21b58fb9-a22a-4bec-a267-717032b78758`에서 현재 web3010 페이지 확인. 실제 상태에서는 기존 관리자 권한 확인 API 실패로 fail-closed 화면이 나타났다.
- 이 탭의 `window.fetch`만 한시 모킹해 ADMIN/미연결/GraphQL 실패/샘플/재조회 UI를 검사했다. MOCK 배너를 표시하고 실제 Tracker 요청은 하지 않았다. 미연결 조회 버튼 3개 disabled, UNKNOWN/캐시/마지막 4자리 마스킹, `/sample`·`/requery` POST 경로 확인. 검증 후 원래 URL 재탐색으로 모킹을 해제했다.
- Orca 스크린샷은 Page.captureScreenshot timeout(탭 가시성/포커스)으로 확보하지 못했다. 접근성 snapshot과 DOM assertion은 성공. 실제 API와 신규 Java 코드를 연결한 브라우저 E2E 성공을 주장하지 않는다.
