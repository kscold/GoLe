# 2026-09-06 백업 작업에 의한 운영 VM 종료

## 확인된 원인

- 한국시간 04:30에 실행된 `gole-data-backup.service`가 실패함
- 새 백업 도구는 `gole_data`를 고정 사용했지만 당시 기존 운영 MinIO는 `gole_default`에 연결되어 있었음
- 동결 요청 전에 복구 마커를 기록한 후 네트워크 연결에 실패했고, 동결 해제도 증명하지 못해 안전 정책이 VM을 종료함
- GCP 감사 로그는 2026-09-05T19:30:50Z에 `compute.instances.guestTerminate`를 기록함
- 비용 watchdog은 종료 직전까지 정상 통과했으며, 백업 로그가 명시적으로 poweroff를 요청함

## 수정

- 고정 MinIO 컨테이너의 네트워크 네임스페이스와 loopback을 사용하여 이전·신규 Compose 네트워크 이름에 의존하지 않게 함
- mc 이미지의 기본 entrypoint 대신 `/bin/sh`를 명시함
- 동결된 S3에 alias 자동 탐색이 걸려 해제를 막지 않도록 S3v4를 명시함
- 실제 고정 MinIO/mc 이미지로 동결·해제와 S3 조회를 검증하는 통합 테스트를 추가함
- 불확실한 동결 상태에서의 종료 정책과 비용 감시 정책은 유지함

## 복구 확인 기준

재기동만으로 복구 완료로 처리하지 않음. 수정한 도구로 `--recover-minio`를 실행한 다음
실제 논리 백업과 `--verify-latest`를 모두 통과시키고, 공개 HTTPS·API readiness·비용 watchdog을
확인해야 함. 실패한 staging과 복구 마커는 수동 삭제하지 않고 검증된 복구 명령으로 처리함.
