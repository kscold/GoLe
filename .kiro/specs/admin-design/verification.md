# OWNER A 검증 — 2026-09-08

## 통과

- Java 21, 디자인 단위/웹/어댑터 테스트 **9개 통과**, 실제 Mongo 7 Testcontainers 통합 테스트 **1개 통과** (skip 0, failures 0).
- 검증: token allowlist/CSS injection/숫자 범위, actor/reason 필수, stale revision, reset/restore, 공개 응답 비밀/조치자 제외, 관리자 경로 전체의 무인증 401/USER 403, ADMIN 조회 허용, malformed publish 400.
- 실제 Mongo: 새 adapter 인스턴스 재조회, 동시에 같은 revision append 시 정확히 1개 성공, 감사 정보 동시 영속화, cursor history, 복원 후 원본 이력 보존. 게시 시간은 Mongo 밀리초 정밀도와 동일하게 정규화했다.
- `pnpm --dir apps/web typecheck`, `pnpm --dir packages/core typecheck` 통과.
- 수정 웹 파일 ESLint와 Prettier 검사, core/design Prettier 검사 통과. Java Spotless는 design 디렉터리만 대상으로 적용했다.
- `node .kiro/specs/admin-design/core-validation.mjs`: allowlist/범위/CSS injection/대비 계산/offline 및 invalid 응답 fallback/published values/Tailwind 기본값 일치 통과.
- Orca 전용 탭 `9038a99b-8f82-4720-9ed0-c37178aa56f2`: API 응답을 해당 탭의 fetch로만 모킹. 실제 디자인 POST는 전송하지 않았다.
- 브라우저: 23개 입력과 세 NAV 링크, 미리보기 색상 변경이 root에 영향을 주지 않음, 잘못된 값 게시 금지, 대비 경고, 사유/검토 후 게시, 게시 후 root 색상 반영, 409 시 입력 유지/재게시 금지, 최신 재조회 후 RESET 게시 및 root 기본값 복귀 확인.
- 실제 CSS viewport 390×843와 1441×1000에서 document 가로 넘침 없음. 결과는 `browser-results.json`에 기록했다. Orca viewport 설정값은 호스트 zoom 영향이 있어 `innerWidth`로 실제 CSS 너비를 확인했다.

## 명확한 제약

- Orca screenshot은 Page.captureScreenshot timeout으로 획득하지 못했다. DOM/접근성 snapshot/실제 계산 스타일/viewport 검증까지 완료했으며, 픽셀 스크린샷 시각 검증 완료로 주장하지 않는다.
- 실행 중인 8090 API에 대한 무변경 GET은 최종 확인 시 connection 실패(HTTP 000)여서, 라이브 인증·Mongo·웹 전체 통합 검증은 수행하지 못했다. API 검증은 MockMvc와 별도 Testcontainers로 완료했다.
- 초기 통합 테스트의 timestamp nano/millisecond 차이와 다른 세션의 일시적 shipping 컴파일 오류는 최종 재검증에서 해소됐다. 다른 세션 소스는 수정하지 않았다.

## 재실행

다른 세션 Gradle 산출물과 충돌하지 않도록 자신의 init script로 `/tmp/gole-admin-design-validation`을 사용한다. 공통 Gradle 파일은 수정하지 않았다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home apps/api/gradlew -p apps/api -I ../../.kiro/specs/admin-design/validation.init.gradle spotlessCheck test --tests 'com.gole.api.design.*' integrationTest --tests 'com.gole.api.design.DesignPersistenceIntegrationTest'
node .kiro/specs/admin-design/core-validation.mjs
```

JUnit XML/HTML 원본: `/tmp/gole-admin-design-validation/test-results/`, `/tmp/gole-admin-design-validation/reports/tests/`.
