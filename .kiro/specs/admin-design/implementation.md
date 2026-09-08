# OWNER A 구현

- `/admin/design`: 23개 허용 토큰(브랜드/포인트/표면/보조 글자 색상, radius, 간격, 기본 글자 크기), 화면에 격리된 미리보기, 변경 사유와 게시 확인, 대비 경고, 초기화/이력 복원, revision 충돌 안내.
- Draft는 화면 메모리에서만 유지한다. 입력만으로 사이트나 Mongo를 바꾸지 않으며 검토 후 명시적으로 게시한다. 새로 불러오기는 편집 취소임을 버튼에 표시한다.
- `/api/admin/design`, `/history`, POST `/publish`, POST `/restore`: 기존 AdminWebConfig/AdminAuthInterceptor의 서버 세션 ADMIN 검증을 그대로 사용한다. 변경 조치자는 request attribute에서만 읽으며 body에서 받지 않는다.
- `/api/v1/config/design`: `{revision,tokens}`만 반환하고 캐시하지 않는다. actorId/reason/action/history는 공개 응답에 없다.
- Hexagonal: domain schema/revision → inbound use case / outbound repository port → service → web/Mongo adapter.
- Mongo `design_revisions`: revision을 고유 `_id`로 사용하는 append-only 문서. 게시 토큰과 actorId/reason/action/publishedAt이 같은 insert이므로 감사 기록과 게시가 분리 성공하지 않는다. 같은 expectedRevision 경쟁은 unique key로 한 건만 성공하고 다른 건은 HTTP 409로 변환한다. 초기 revision 0은 CSS 기본값이며 읽기 때문에 DB에 쓰지 않는다.
- 복원은 과거 문서를 수정하지 않고 새 revision을 게시한다. sourceRevision 0은 RESET, 그 외 RESTORE:n으로 감사 정보를 남긴다. 이력은 25개 단위 `_id < before` cursor 조회다.
- `packages/core/src/design`의 기존 wildcard subpath export `@gole/core/design` 사용. 공통 core barrel과 manifest 변경 없음.
- shared entrypoints: RootLayout에 DesignTheme 연결, globals.css html 기본 font-size 변수 연결, admin-shell에 디자인 토큰/운영 자동화/배송 연동 NAV 추가 완료.
- DesignTheme는 초기 HTML/CSS 기본값을 유지한 뒤 검증된 공개 테마만 적용한다. 실패/잘못된 응답/2.5초 timeout은 기본값으로 복귀한다. 게시 이벤트와 탭 focus 때 갱신하고, 이전 요청이 늦게 도착해 최신 테마를 덮지 않게 순서를 검사한다.
- 임의 CSS/JS, URL, style declaration, 알 수 없는 key는 받지 않는다. 색상은 6자리 HEX, 숫자는 명시 단위/범위로 제한한다.

## 출시 단계 정렬

현재 사용자 지시의 community-first/direct trade 경계를 변경하지 않는다. 테마 토큰은 출시 단계/feature gate/결제/배송 운영 설정을 포함하지 않으며 해당 gate를 여는 API도 호출하지 않는다. 미리보기는 컬렉션 탐색 예시이며 구매·결제 버튼이 아니다.

## 공통 통합 요청

필수 공통 수정 없음. 중앙 `/admin/audit`의 enum/barrel 변경 대신 디자인 화면에 atomic 감사 이력을 제공한다. 향후 중앙 감사 피드에서 합산하려면 디자인의 읽기 포트를 중앙 read model에 연결할 수 있으나 이 작업의 필수 활성 조건이 아니다.
