# Java 기준 구현의 실험 기록

실패도 원인과 변경 근거이므로 성공 실행과 함께 보존한다. archive의 Python/이전 Java 수치와 섞지 않는다.

| 실행 | 결과 | 분석 |
|---|---|---|
| 20260909-011321-469 | 최초 워밍업: 결제 접수 500 3건, dropped iteration 1건. 본 측정 미실행 | [review](20260909-011321-469-baseline-purchase-spike/review.md) |
| 20260909-134159-227 | 301건 모두 확정했으나 300/300 고정 판정으로 중단. 본 측정 미실행 | [review](20260909-134159-227-baseline-purchase-spike/review.md) |
| 20260909-204228-733 | 워밍업 301건 + 본 측정 10 RPS/60초 602건 모두 확정 | [review](20260909-204228-733-baseline-purchase-spike/review.md) |

## Target v1 pre-steady-state / harness diagnosis

- [20260911-232830-531-target-worker-normal](20260911-232830-531-target-worker-normal/error.txt): observer가
  `D:\target-state.sql`을 찾다가 실패해 k6 본 측정 전에 중단됐다. 서비스 성능 결과가 아니다.
  실제 PowerShell Start-Job 파일 문맥 오류로, `1f23e63`의 absolute script path 호출과
  [observer process 회귀 검사](../../ops/performance/target-observer-test.ps1)의 근거다.
- [20260911-234502-053-target-worker-normal](20260911-234502-053-target-worker-normal/config.json):
  old SLO, no embedded warmup, cold Worker normal 10 RPS/30초, VUs 10/MaxVUs 50.
  288 iterations, dropped 13, HTTP 202 228건/500 60건/client timeout 4건, Payment Hikari counter 0→60.
  DB는 232건 확정·pending=0으로, 응답 유실과 Worker 미완료를 구분해야 한다.
  [별도 진단](../target-v1/20260911-worker-diagnosis/review.md)은 초기 오류 집중과 cold/steady-state 분리의 근거다.
  새 warmup 이후 Target 결과와 직접 비교하거나 “Worker는 10 RPS도 처리하지 못한다”고 해석하지 않는다.

두 실행은 `e217daa`에서 역사적 evidence로 보관했다. 원본 artifact는 수정하지 않는다.
legacy result.json의 “Worker 32/s required, 40/s normal target”은 당시 review snapshot이며 현재 계약이 아니다.
현재 계약은 [performance](../../docs/performance.md), 사용 가능한 주장은 [claim ledger](../../docs/portfolio/portfolio-evidence.md)를 따른다.

## Git 보관 범위

- 실행 조건/코드 식별, summary, DB 결과, 로그, 관측 시계열, 해석을 함께 커밋한다.
- k6 요청별 `raw.json`, `warmup-raw.json`은 기존 .gitignore에 따라 로컬에 보존한다.
  최신 실행의 별도 p99 집계는 measured-metrics.json에 원시 파일 SHA-256과 계산 방법을 남겼다.
- 실행하면 폴더가 자동 생성되지만 자동 commit/push는 하지 않는다. 결과를 검토한 뒤 명시적으로 반영한다.
- compose의 자격증명은 이 저장소에 이미 공개된 로컬 전용 값이다. 실제 서비스 자격증명을 결과에 포함하지 않는다.

한 번의 낮은 부하 성공은 전체 비즈니스 목표나 최대 처리량 달성의 증거가 아니다.
