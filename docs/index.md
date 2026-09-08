# GoLe 지식 지도

이 저장소는 **옵시디언 볼트**다. 저장소 루트를 볼트로 열면 스펙·운영 문서·규약이 한 그래프로 이어진다.

> **여기에 문서 내용을 복제하지 않는다.** 스펙과 규약은 각자의 자리에 있고, 이 문서는 그리로
> 가는 길만 안내한다. 복제본은 반드시 원본과 어긋나며, 어긋난 뒤에는 어느 쪽이 맞는지 아무도 모른다.

---

## 먼저 읽을 것

| 문서 | 언제 |
|---|---|
| [온보딩](onboarding.md) | **처음 왔을 때.** 환경 구성부터 첫 기여까지 |
| [러닝북](operations/runbook.md) | **뭔가 안 될 때.** 증상별 대응 |
| [외부 서비스 대장](external-services.md) | Firebase·Apple·포트원·GCP 식별자를 찾을 때 |
| [README](../README.md) | 프로젝트 전체 그림, 스택, 기능 목록 |
| [개발 규약](../.kiro/steering/dev-conventions.md) | 커밋·PR·레이어 분리·SDD 절차 |
| [배포](../.kiro/steering/deploy.md) | 서버·pm2·CD를 건드릴 때 |
| [브랜드 아이덴티티](../.kiro/steering/brand-identity.md) | 색·타이포·톤. UI를 새로 만들 때 |
| [MinIO](../.kiro/steering/minio.md) | 이미지 업로드·버킷·공개 URL |

## 운영 문서

실제로 돌아가는 것들의 동작 방식.

- [러닝북 — 증상별 대응](operations/runbook.md)
- [분석 동의](operations/analytics-consent.md)
- [디스코드 알림 라우팅](operations/discord-routing.md)
- [미디어 생명주기](operations/media-lifecycle.md)
- [포트원 카카오페이](operations/portone-kakaopay.md)
- [제3자 제공 동의](operations/third-party-provision-consent.md)

## 스펙 (SDD)

기능 단위로 `.kiro/specs/<기능>/`에 `requirements.md` → `design.md` → `tasks.md`를 둔다.
**구현보다 스펙이 먼저다.**

- 전체 목록: [`.kiro/specs/`](../.kiro/specs/)
- 현재 통합 기준: [관리자 고도화·브랜치 기획 정렬](../.kiro/specs/admin-alignment/spec.md)
- 감사 기록: [2026-08-03 감사](../.kiro/specs/AUDIT-2026-08-03.md)

스펙을 읽을 때는 `tasks.md`의 체크박스보다 **`requirements.md`의 "범위 밖"** 절을 먼저 본다.
무엇을 하지 않기로 했는지가 대개 더 중요하다.

---

## 볼트 사용 규칙

이 저장소의 기여자는 대부분 AI 에이전트(Claude Code·Codex)다. 볼트가 쓰레기장이 되지 않게 몇 가지를 지킨다.

**1. 새 문서는 자리를 먼저 정한다.**

| 성격 | 위치 |
|---|---|
| 기능의 요구사항·설계·작업 | `.kiro/specs/<기능>/` |
| 도구에 중립적인 규약·지침 | `.kiro/steering/` |
| 돌아가는 시스템의 동작·운영 절차 | `docs/operations/` |
| 위 어디에도 안 맞는 것 | 대개 문서가 아니라 코드 주석이어야 한다 |

**2. 링크는 상대경로로 건다.** 볼트 밖(웹 GitHub)에서도 클릭되어야 한다. 위키링크(`[[...]]`)는
볼트 안에서만 동작하므로, 같은 폴더 안을 잇는 경우에만 쓴다.

**3. 개인 메모는 커밋하지 않는다.** `.obsidian/workspace.json` 같은 UI 상태는 gitignore 대상이다.
공유할 가치가 없는 초안은 볼트 밖에 둔다.

**4. 문서가 코드와 어긋나면 문서를 고친다.** 규약 문서끼리 충돌하면
[개발 규약](../.kiro/steering/dev-conventions.md)이 이긴다. 양쪽에 서로 다른 규칙을 남겨 두지 않는다.
