# 운영 활성 상태

구현·로컬 검증 완료와 운영 활성은 구분한다.

- 소스의 RootLayout 및 NAV에는 연결 완료. 기존 web3010이 소스 변경을 반영하여 디자인 경로를 렌더링함을 확인했다.
- 현재 api8090의 최종 read-only GET `/api/v1/config/design`, `/api/v1/config/launch`는 HTTP 000(connection 실패). 현재 런타임의 실제 launch stage나 신규 endpoint 활성 상태는 확인 불가다.
- 기존 dev server 중단/재시작, pnpm install, commit/push/reset/restore, 운영 DB 게시, 외부 메시지 및 비용 발생 작업을 하지 않았다.
- 테스트 Mongo는 Testcontainers가 만든 별도 임시 DB이며 운영 DB가 아니다. 브라우저 게시/충돌/복원은 전용 탭의 로컬 fetch 모킹이다.
- 배포/프로세스 갱신 후 새 API 클래스가 활성화되면 기본 revision 0을 읽고, 관리자 첫 게시에서 `design_revisions`를 생성한다. 필수 추가 설정/secret/common barrel 수정은 없다.
- 실제 게시된 테마는 첫 HTML 이후 클라이언트 로딩으로 적용되므로 기본 테마가 먼저 보일 수 있다. 새 페이지 로드/탭 focus/자신의 게시 이벤트에서 갱신하며 실시간 push 구독은 없다.
- CSS/서버/core 스키마 변경 시 세 값을 함께 검토하고 schema parity 검증을 실행한다. 생성 보조 스크립트 `generate-schema.py`는 현재 Tailwind 중 승인된 변수만 추출하며, 실행 후 TS/Java 포맷을 적용해야 한다.
- 중앙 통합 검증 담당 coordinator가 API 활성화가 허용된 시점에 실제 관리자 세션 GET/POST/충돌/복원/공개 GET을 확인하면 된다. 이 문서는 서버 재시작을 요청하거나 실행하는 승인이 아니다.
