# Target v1 부하 자동화 준비 검증 — 2026-09-11

실제 부하는 실행하지 않았다. k6 실행/inspect, 대량 반복 HTTP, saturation, 300초 실제 대기 시험은 모두 미실행이다.
이번 결과는 자동화와 기능 변경의 검증이며 성능 수치가 아니다.

## 변경 범위

- 공통 `ops/performance.ps1 -Mode target`의 Check / Prepare / Run 분리.
- Worker / Waiting / Reservation / Isolation / Business 독립 k6 진입점, 시드 고정 사용자 행동.
- SQL fixture, HTTP/DB/Redis/Prometheus/컨테이너·생성기 관측, 오프라인 결과 판정.
- 반환 대기 polling 12초→1초. READY·300초 hold·DB 원장·역할 분리 유지.
- [Target 실행 가이드](../../../docs/target-v1-load-guide.md), [Mermaid 시나리오](../../../docs/target-v1-load-scenario.md), 기존 문서의 baseline/Target 범위 정리.

## 수행한 검사

| 검사 | 결과 |
|---|---|
| PowerShell Parser / JavaScript `node --check` | 새 스크립트 및 공통 진입점 문법 통과 |
| Docker Compose config --quiet | baseline + Target + 성능 관측 오버레이 병합 통과. 앱 기동/부하 없음 |
| `node --experimental-vm-modules ops/performance/target-static-test.mjs` | 14개 통과. k6 모듈·HTTP·시계·sleep을 모의 구현으로 교체. 실제 네트워크/대기 없음 |
| `pwsh -File ops/performance/target-review-test.ps1` | 5개 통과. 정상 자료도 requires_review, 미확정·threshold 실패·Hikari timeout·누락 자료는 failed |
| Gradle `--offline test --tests com.limitedgoods.WaitingTest --tests com.limitedgoods.WorkerTest bootJar` | Waiting 5개 + Worker 2개, 실패 0. bootJar 성공 |
| PostgreSQL SQL fixture/state/timeline | 임시 실제 PostgreSQL의 limited_goods_test에서 기존 migration 스키마를 사용해 HELD 3건 생성·집계. 정합성 위반 0 |

Docker가 sandbox에서 거부되어 정상 권한 상승으로 검사했다. ACL/sandbox 설정은 변경하지 않았다.
검사 시작 당시 실행 중 Docker 컨테이너는 없었다. 기능 검사 전용 임시 Redis/PostgreSQL만 만들고 검사 후 둘 다 종료·자동 제거했다.
기존 dev/perf 데이터·볼륨과 archive는 변경하지 않았다. PostgreSQL 전체 Spring 계약 테스트를 재실행한 것은 아니다.

## 남은 실행 검증

Prepare → Check → 각 Run의 실제 컨테이너 실행, k6 엔진 호환성, 관측 비용·실패 시 수집 완전성은 사용자 실행으로 확인해야 한다.
오프라인 모의 실행은 실제 k6 엔진 실행을 대체하지 않는다. Mermaid는 Markdown 소스로 작성했으며 별도 이미지 렌더 결과는 포함하지 않는다.
50,000명 처리·Worker 40/s·5초 재구매·결제 성능 격리·실제 300초 보유는 여전히 미검증이다.
현재 기존 HELD fixture의 Worker/Isolation 공급 기간은 최대180초다. 단일5분 측정에는 시간에 맞춘 fixture 보충이 필요하다.
