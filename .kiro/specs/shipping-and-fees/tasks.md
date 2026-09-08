# 배송 추적 기반 거래 보호 · 수수료 — 구현 태스크

> 2026-08-20: B·C·P·Q·S·D·E 전 구간 구현 완료. 스텁 트래커로 로컬 전 시나리오
> (등록 → 이동중 → 배송완료 → 자동확정 → 정산 5%, 분쟁 → 판정 환불 → 정산 미생성)를
> 실측 검증했다. 남은 것은 F(실 트래커 자격증명 주입)뿐이며, 어댑터·매핑은 이미 있다.

## A. 수수료 외부화 (완료 — 2026-08-04)
- [x] A1 `FeePolicy` 값 객체(요율 범위·하한>상한 금지 불변식, 반올림·클램프) + 테스트 9건
- [x] A2 `FeeProperties` `@ConfigurationProperties("gole.fee")` +
      application.yml `GOLE_FEE_RATE`/`GOLE_FEE_MIN`/`GOLE_FEE_MAX` 플레이스홀더
- [x] A3 `Settlement`에 `feeRate` 추가, `compute(..., FeePolicy, ...)`로 시그니처 변경
- [x] A4 `SettlementDocument.getFeeRate()` 레거시 보정(null → 0.05)
      ※ 참고: 이 임베디드 문서는 현재 **아무도 쓰지 않는다**(`StubSettlementAdapter`가
      로그만 남기고 저장하지 않음). 실 정산 도입 시 곧바로 쓸 수 있도록 필드만 준비해 둔 상태다.
- [x] A5 `PLATFORM_FEE_RATE` `@Deprecated` 처리(삭제 안 함 — 설정 기본값 출처)
- [x] A6 소액 거래 방어 테스트 포함(하한 > 거래액이어도 payout 음수 불가)
- [x] A6b 환불 주문 무수수료 **회귀 테스트**(R5.5) — 현재 `REFUNDED`는 정산을 만들지 않아
      동작상 충족이나, 이를 고정하는 테스트가 아직 없다
- [x] A7 관리자 수수료 집계 API(R5.6) — `admin` 컨텍스트, 병행 리팩터링 종료 후
- [x] A8 공개 수수료 정책 API와 매물 등록 예상 수수료·정산액 고지(R5.7), 조회 실패 시
      추정값 미표시 및 재시도 제공

## B. shipping 컨텍스트 — 도메인·포트
- [x] B1 `WaybillNumber` 값 객체(정규화·검증 불변식) + 테스트
- [x] B2 `Carrier` enum, `DeliveryStatus` enum(원문 `rawStatus` 보존)
- [x] B3 `Shipment` 애그리거트(상태 역행 금지, 운송장 변경 이력, `deliveredAt` 1회)
- [x] B4 in-port: `RegisterWaybillUseCase`, `TrackShipmentUseCase`, `GetShipmentUseCase`
- [x] B5 out-port: `ShipmentRepositoryPort`, `DeliveryTrackerPort`, `TrackerCachePort`
- [x] B6 `ShipmentService`(등록 시 판매자 검증 R1.2, 추적·전이, 알림 발행)
- [x] B7 도메인·서비스 테스트(가짜 트래커로 전이 검증)

## C. shipping 컨텍스트 — 어댑터
- [x] C1 Mongo 영속성 어댑터 + 도큐먼트
- [x] C2 `StubDeliveryTrackerAdapter` — 경과시간 기반 상태 시뮬레이션(기본 빈)
- [x] C3 `RedisTrackerCacheAdapter` — 상태별 TTL, 장애 흡수
- [x] C4 `ShipmentController` — 운송장 등록/조회
- [x] C5 알림 연동(운송장 등록 R1.5, 배송완료 R2.4)

## P. 파이프라인 엔진 (R7/R9 — 무개입 원칙의 핵심)
- [x] P1 `@ConfigurationProperties("gole.pipeline")` — 상태별 타임아웃 전부 외부화(R9.1)
- [x] P2 `orders`에 `(status, statusChangedAt)`, `shipments`에 `(status, deliveredAt)` 인덱스
      ※ 인덱스 **이름 확정 후** 배포 전 기존 인덱스 충돌 확인(`follows` 부팅실패 전례)
- [x] P3 `PipelineRule` 추상 + 규칙 구현(결제 만료 / 미발송 독촉 / 미발송 자동환불 /
      배송정체 / 자동 구매확정 / 추적불가)
- [x] P4 `OrderPipelineScheduler` — 규칙 순회, 건별 예외 격리(R7.4)
- [x] P5 자동 전이는 기존 유스케이스 호출로만 구현(새 경로 금지) + 멱등성 테스트(R7.3)
- [x] P6 자동 전이 시 알림 발행(R7.5)
- [x] P7 고정 `Clock` 기반 타임아웃 시나리오 테스트(각 규칙별 경계값)

## Q. 예외 큐 (R7.6)
- [x] Q1 예외 사유 모델(분쟁 / 배송정체 / 미접수 / 추적불가 / 판정지연)
- [x] Q2 예외 큐 조회 API — 정상 진행 건은 제외
- [x] Q3 `admin-console` 스펙과 **화면 통합**(별도 관리자 UI 신설 금지)

## S. CS 연락처 (R8)
- [x] S1 `PhoneNumber` 값 객체(정규화·검증 불변식) + 테스트
- [x] S2 주문 생성 시 구매자 연락처 수집(R8.1), 판매자 연락처(R8.2)
- [x] S3 응답 DTO **기본 마스킹** + 전체 번호 전용 엔드포인트(당사자/ADMIN 게이트) (R8.4)
- [x] S4 운영자 전체 번호 열람 감사 로그 — 기존 `admin_actions` 재사용 (R8.5)
- [x] S5 목적 외 사용 금지 고지 문구(R8.6)

## D. order 연동
- [x] D1 `OrderStatus`에 `DISPUTED` 추가 + 전이 규칙(FUNDS_HELD에서만 진입)
- [x] D2 `OpenDisputeUseCase` / `ResolveDisputeUseCase`(환불 또는 완료)
- [x] D3 `AutoCompleteOrdersScheduler` — DELIVERED + N일 + 무분쟁 → 기존 `CompleteOrderUseCase` 호출
- [x] D4 미발송 장기화 시 구매자 일방 환불(R4.5)
- [x] D5 자동 구매확정 멱등·타이머 정지 테스트(고정 `Clock`)

## E. 프론트
- [x] E1 `entities/shipment` 타입 + API
- [x] E2 `features/register-waybill` — 판매자 운송장 입력
- [x] E3 `features/open-dispute` — 구매자 분쟁 제기
- [x] E4 `widgets/shipment-tracker` — 배송 타임라인(표현 전용, props 주입)
- [x] E5 `views/order-detail` 조립
- [x] E6 관리자 분쟁 화면 — 배송 사실 근거 표시(R4.3)

## F. 실 트래커 연동 (자격증명만 남음)
- [ ] F1 **사용자 입력 대기** — Delivery Tracker(tracker.delivery)로 확정. 남은 것:
      https://tracker.delivery 에서 클라이언트 생성 후
      `SHIPPING_TRACKER_ENABLED=true` + `SHIPPING_TRACKER_CLIENT_ID/SECRET` 주입
- [x] F2 `DeliveryTrackerApiAdapter`(GraphQL) + `@ConditionalOnProperty` 게이트 — 스텁과 동일 게이트 패턴
- [x] F3 Delivery Tracker 표준 상태 코드 → `DeliveryStatus` 매핑 테이블(미지 코드는 UNKNOWN → 예외 큐)
- [ ] F4 실 송장으로 엔드투엔드 스모크

## 검증/배포
- [x] V1 `./gradlew test` + 통합 테스트 통과
- [x] V2 프론트 build / typecheck / fsd:lint 통과
- [x] V3 스텁 트래커로 전 구간 로컬 시나리오(등록 → 이동중 → 배송완료 → 자동확정 → 정산)
- [x] V4 커밋·배포

## 후속
- [ ] 판매자 정산 실송금(현재 `StubSettlementAdapter`는 로그만 남긴다)
- [ ] 수수료 프로모션(신규 판매자 면제, 카테고리별 차등)
- [ ] 반품 배송(역방향 운송장)


## 구현 노트 (2026-08-20 실측)

- D3의 `AutoCompleteOrdersScheduler`는 별도 스케줄러가 아니라 `OrderPipelineScheduler`의
  규칙(`AutoCompleteDeliveredRule`)으로 흡수했다 — 설계 P절의 "하나의 스케줄러가 정책
  테이블을 읽어 도는 구조"가 우선한다.
- R9의 결제 만료 규칙(`PaymentPendingExpiryRule`)은 **스텁 결제 모드에서만** 활성이다.
  실 PG에서는 기존 `PaymentReconciliationScheduler`가 원장을 대조한 뒤 만료한다 —
  원장 확인 없는 시간 만료는 웹훅이 늦은 실결제를 죽일 수 있다.
- S2(판매자 연락처)는 프로필이 아니라 **운송장 등록 시점**에 수집한다. account 컨텍스트에
  프로필 수정 유스케이스 자체가 없어서다. 프로필 등록 경로는 후속.
- 예외 큐(Q)는 별도 컬렉션이 없다 — 주문·배송 상태에서 **매번 계산**한다(유령 예외 방지).
  등재 1회 알림만 `pipeline_markers`(유니크 인덱스)로 멱등 처리한다.
- R4.5는 "발송 전 즉시 일방 환불 허용 + 발송 후 차단(분쟁 유도)"으로 구현했다. 기존에는
  구매자가 아무 때나 환불할 수 있었는데, 배송 개념이 생기면서 이동 중 자금 회수를 막아야 했다.
- 주문 파이프라인 인덱스: `orders.order_status_changed_at_idx(status, statusChangedAt)`.
  `statusChangedAt`은 새 필드라 **이 배포 이전 문서에는 없다** — 없는 문서는 파이프라인
  후보 조회에 잡히지 않으며, 다음 저장 시 채워진다. 기존 주문 소급이 필요하면 백필:
  `db.orders.find({statusChangedAt:null}).forEach(o => db.orders.updateOne({_id:o._id},
  {$set:{statusChangedAt:o.statusHistory.at(-1).occurredAt}}))`
- 로컬 함정: 호스트에 brew Redis가 127.0.0.1:6379를 선점하고 있으면 앱은 그쪽에 붙는다.
  `scripts/seed-e2e-accounts.sh`는 Docker Redis에 세션을 심으므로 세션이 "없는 것처럼"
  보인다(INVALID_SESSION). 호스트 redis-cli로 같은 키를 넣거나 brew Redis를 내리면 된다.

## 2026-09-08 OWNER C 후속

- [x] 기존 실 어댑터 GraphQL 오류/HTTP 오류/UNKNOWN 구분, 고정 endpoint/timeout/호출 예산/동시조회 캐시 보강.
- [x] 관리자 `/admin/integrations/tracker` 준비상태·연결 검증·샘플 조회·기존 주문 안전 재조회 API/UI. 안전 재조회는 배송 저장 및 알림을 일으키지 않는다.
- [x] ADMIN validation/감사 저장·마스킹, 자격증명 미설정 시 스텁 미호출. 배송 영역 테스트 45건 통과, 웹 타입/린트/FSD 및 Orca 별도 탭 모킹 검증.
- [ ] F1/F4 실제 자격증명 주입 및 실 송장 E2E는 여전히 미완료. 서버 재시작/실제 요청 없음.
- 세부 계약·검증 한계·공통 소유자 후속: `../admin-tracker/implementation.md`.
