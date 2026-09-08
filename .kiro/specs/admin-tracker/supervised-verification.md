# OWNER C supervised 후속 검증

검증일: 2026-09-08. Task `task_874edf14e823`, Dispatch `ctx_5cb7720d5920`.
이 문서는 이전 untracked 구현을 새 Dispatch에서 재검증한 결과다. 구현 전체 계약·인수인계는 `implementation.md`에 있다.

## 판정

- 기존 배송 Tracker 관리자 API/UI 구현과 로컬 모킹 검증 완료.
- **실제 연결 준비 완료 또는 실연동 성공은 아님.** 이번 작업에 실제 Client ID/Client Secret이 제공되지 않았고, provider 호출을 하지 않았다. enabled/configured와 connected를 구분하는 기존 응답/UI를 유지했다.
- 현재 실행 상태를 읽으려 한 `GET http://localhost:8090/api/v1/config/launch`는 연결 거부(curl 7)였다. 따라서 실제 저장된 현재 출시 단계와 실행 중 서버의 credential 상태는 이 작업에서 확정하지 못했다. 서버를 시작하거나 재시작하지 않았다.

## 현재 출시 단계와 정렬

기준은 `.kiro/specs/launch-stage/spec.md`, `LaunchConfig`, `LaunchFeature`다.

- 서버 미설정 기본은 Stage 1 `BROWSE_ONLY`; 공개 설정 통신 실패 시 클라이언트는 Stage 0으로 닫는다. 현재 런타임 단계를 기본값으로 대체하여 보고하지 않는다.
- Stage 0/1은 `DIRECT_CHAT`이며 플랫폼 주문·결제·자동 지급을 제공하는 단계가 아니다. 신규 판매·직거래 연결에는 별도 판매자 신원확인 래치도 필요하다.
- Stage 2 `TRADING`/Stage 3 `FULL` 전환은 기존 결제 준비상태·운영 확인·정산 실행모드 가드가 결정한다. **Tracker configured/connected는 해당 승인이나 출시 단계 변경을 대신하지 않는다.** Tracker 관리자 API는 출시 설정을 읽거나 변경하지 않으며 출시 단계의 override도 추가하지 않았다.
- 이 화면의 안전 재조회는 이미 존재하는 shipment만 조회한다. 주문/배송을 생성하거나 저장하지 않고 결제·정산·자동확정·알림 포트를 호출하지 않는다. 따라서 기존 배송 조사 도구를 제공하는 것이 신규 거래 기능을 개방했다는 뜻은 아니다.
- 기록된 샘플 결과/캐시는 배송 관찰값이다. 캐시를 기존 scheduler가 후속 조회에서 사용할 수 있으나, 관리자 재조회 요청 자체는 scheduler나 주문 전이를 실행하지 않는다. 기존 자동화 정책은 별도의 소유 범위다.
- `LaunchStage` 일부 주석의 초기 공개 문구와 launch 명세의 Stage 0 공사중 설명 차이는 확인했지만 공통 launch 소유 코드는 수정하지 않았다. 실제 출시 단계/Obsidian 기획 정렬 최종 판단은 coordinator 소유다.

## 이번 재검증과 변경

- 기존 공식 문서 검토를 재사용했다: `https://tracker.delivery/docs/authentication`, `https://tracker.delivery/en/docs/client-libraries/nodejs-graphql-request`. 고정 GraphQL endpoint, API-key header, track variables, HTTP 200 GraphQL 오류 처리 계약은 기존 테스트와 일치한다.
- 기존 MockMvc 테스트로 `/api/admin/**` ADMIN 인터셉터의 401/403/ADMIN 동작, 입력 validation, credential 비반환을 재확인했다.
- 자격증명 누락/미연결 스텁 미호출, HTTP/GraphQL 실패, UNKNOWN 매핑, 고정 endpoint, timeout, 분당 요청 예산, 60초 캐시/동시 polling, 감사 저장 실패 시 외부 호출 차단 테스트를 재실행했다.
- `TrackerAdminServiceTest.liveDeliveredRequeryDoesNotPersistOrTriggerOrderFlow`를 추가했다. 캐시가 비어 있고 provider가 DELIVERED를 반환해도 원래 Shipment는 PENDING/deliveredAt=null이며 repository 읽기 외 저장 호출이 없음을 검증한다. 기존 cache-hit DELIVERED 테스트와 함께 두 경로를 보장한다.
- core/web 타입 검사, 소유 웹 경로 ESLint 통과. 공통 Gradle/보안설정 변경 불필요. 추가 세션, commit/push, install, 서버 재시작, 실제 provider/외부 메시지 호출 없음.

## 남은 실연동 단계

1. Delivery Tracker Console에서 프로젝트 Client ID/Client Secret을 발급한다.
2. API 프로세스의 비밀 환경변수에 `SHIPPING_TRACKER_CLIENT_ID`/`SHIPPING_TRACKER_CLIENT_SECRET`, 활성 플래그 `SHIPPING_TRACKER_ENABLED=true`를 주입한다. 기존 `apps/api/src/main/resources/application.yml`이 매핑한다. 웹 공개변수·DB에 보관하지 않는다.
3. 별도 운영 절차로 API를 배포/재기동한 뒤 ADMIN 상태 조회 → 연결 검증 → 조회 권한 있는 실제 송장 샘플을 검증한다. 이번 작업에서는 실행하지 않았다.
4. 실송장 E2E가 끝날 때까지 shipping F1/F4와 실연동 성공 표시는 미완료로 남긴다. 중앙 감사 화면 연계 및 다중 인스턴스 한도는 기존 `implementation.md` 후속을 참조한다.

최종 실행은 shipping 테스트 선택 명령으로 `BUILD SUCCESSFUL in 6s`를 확인했다. 기존 45개에 새 회귀 테스트 1개를 추가했다. 이후 공유 `build/test-results/test`의 shipping XML이 다른 검증 실행으로 교체된 것으로 보여 XML 집계는 사용할 수 없었으며, 이번 판정은 해당 실행의 성공 출력에 근거한다.
