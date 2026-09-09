# 부하테스트 실행 가이드 — Git Bash

300/300 고정 판정 수정 후 사용자 실행에서 워밍업 301건과 본 측정 602건이 모두 확정됐다.
초기 지연은 예열 자료로 남기고, 응답 시간 목표는 본 측정에 적용한다.
실행별 조건·결과·한계는 [실험 기록](../artifacts/performance/README.md)에 보존한다. 아래 실행 명령은 동일하다.

## 현재 준비된 것

Java/Spring 구매·점유·결제·만료, API 2개/Nginx/Worker/Mock PG,
PostgreSQL의 dev/test/perf DB, Redis gate, 기능 테스트 22개가 있다.
공통 실행은 `ops/performance.ps1`, 요청 패턴은 `k6/purchase-spike.js`가 담당한다.
워밍업과 본 측정이 공유하는 요청 흐름은 `k6/lib/purchase-flow.js`에 있다.
코드 읽기는 [learning.md](learning.md), 자원·합격 기준은 [performance.md](performance.md)를 본다.

## 1. 지금 가능한 읽기 전용 확인

Git Bash에서 JDK 21, Docker Desktop, PowerShell 7(`pwsh`)을 사용할 수 있어야 한다.

```bash
cd /d/Code/limited-goods-reservation
pwsh -NoProfile -File ./ops/performance.ps1
```

기본값은 `-Action Check`다. Compose 문법, 7개 서비스 상태, Nginx를 경유한 판매 조회 응답을 확인한다.
기동/초기화/구매/결제/k6는 실행하지 않는다. 기존 dev 또는 perf 환경 모두 확인할 수 있다.
서비스가 꺼져 있으면 알려주고 종료한다. health가 ready여도 성능 측정 전체가 검증된 것은 아니다.

## 2. 환경 준비와 실험은 명시적으로 구분

| Action | 동작 | 데이터와 부하 |
|---|---|---|
| Check | 현재 서비스와 API 읽기 확인 | 변경 없음 |
| Prepare | JAR 빌드, 이미지 빌드, perf 마이그레이션·초기화, 서비스·Prometheus 기동, 준비 확인 | perf 데이터 삭제, 부하 없음 |
| Run | Prepare 후 선택한 실험 한 번 실행, 결과 저장, 종료 | perf 데이터 삭제 + 실제 부하 |

Prepare/Run 전에 다른 부하 발생기와 로컬 테스트를 종료하고, 이전 결과를 보존한다.
같은 Compose 프로젝트를 perf로 전환하므로 dev 앱은 중단되지만 dev DB와 기존 볼륨은 유지한다.
goods-k6가 이미 실행 중이면 중단한다. 별도 이름으로 실행한 도구까지 자동 탐지하지는 않는다.
원래 셸의 DB/gate 환경 변수는 명령이 끝나면 복원한다. 기동한 컨테이너는 perf 상태로 남는다.

환경만 준비하고 멈추려면:

```bash
pwsh -NoProfile -File ./ops/performance.ps1 -Action Prepare -Mode baseline
```

baseline은 Redis gate OFF, gate는 ON이다. CPU·메모리·pool 구성은 같다.
Run은 재현성을 위해 준비·초기화를 다시 수행하므로 Prepare를 먼저 실행할 필요는 없다.
Run은 별도 판매에서 10 RPS·30초 워밍업을 수행한다. 실제 성공한 구매 수를 원시 결과에서 읽고,
그 판매의 주문·점유·결제 확정 수와 대조한다. 300건 또는 301건이어도 실제 처리 수와 일치하면 된다.
성공 구매가 0건이거나 예상 밖 오류·요청 누락·정합성 위반·미확정 작업이 있으면 본 측정으로 넘어가지 않는다.
API 2개/Worker/Mock PG의 Hikari timeout 증가와 프로세스 재시작도 검사한다. 확정 대기는 기존처럼 최대 약 30초다.
409/429 거절 건수도 warmup-evidence.json에 별도 기록하며, 성공 점유 건만 확정 수와 대조한다.
워밍업에는 지연시간 threshold를 두지 않는다. 초기 p95/p99는 관측값이며 steady-state SLO 판정에 섞지 않는다.
후반부 안정화 여부는 결과 분석에서 확인한다. 위 조건 통과가 JVM 성능의 완전한 안정화를 보장하지는 않는다.
통과하면 앱을 재기동하거나 DB를 다시 초기화하지 않고, 새 판매에서 본 측정을 시작한다.

## 3. 직접 실행할 명령

Docker Desktop을 실행한 뒤 Git Bash에서 아래 명령을 사용한다. 별도 Prepare나 수동 DB 초기화는 필요 없다.
워밍업 통과 시 10 RPS × 60초 본 측정까지 자동 진행하며, 실패하면 재시도 없이 자료를 남기고 중단한다.
JFR은 이번 실행 절차에 추가하지 않았다. 기존과 같은 상세 진단 조건을 유지하려면 -Diagnostics를 사용한다.

```bash
cd /d/Code/limited-goods-reservation

# 낮은 요청량에서 실행·수집 절차를 확인하는 예시. 비즈니스 목표 수치가 아니다.
pwsh -NoProfile -File ./ops/performance.ps1 -Action Run -Mode baseline -OpeningRps 10 -TailRps 10

# 초기 지연 진단: 같은 부하 조건 + 구매 단계 로그/워밍업 DB 대기 표본
pwsh -NoProfile -File ./ops/performance.ps1 -Action Run -Mode baseline -OpeningRps 10 -TailRps 10 -Diagnostics

# 목표 도착 부하 예시. 이전 결과를 확인한 뒤 별도로 실행한다.
pwsh -NoProfile -File ./ops/performance.ps1 -Action Run -Mode gate -OpeningRps 3000 -TailRps 400
```

RPS를 생략한 Run은 거절한다. 자동 증가·반복·후속 개선은 하지 않는다.
현재 시나리오는 재고 1,000개를 생성하고 첫 10초 OpeningRps, 다음 50초 TailRps의 최초 구매를 보낸다.
성공 점유의 결제 접수도 추가하므로 전체 HTTP RPS는 구매 RPS보다 높다.
다른 요청 행동은 별도 k6 파일로 추가하고 스크립트의 Scenario 허용 목록을 확장한다.
지금은 purchase-spike 하나만 지원한다. 이 패턴의 RPS 변경만으로 모든 시나리오를 대신하지 않는다.

## 4. 자동 저장되는 결과

결과는 `artifacts/performance/시각-mode-scenario/`에 모인다.

| 파일 | 용도 |
|---|---|
| run.json, commit.txt, git-status.txt, working-tree.patch, jar-hash.json | 실행 조건·코드 식별·완료/실패 상태 |
| compose.yaml, scenario.js, lib/purchase-flow.js | 실행 설정·시나리오·공통 요청 흐름 복사 |
| k6.log, exit-code.txt, summary.json, raw.json | 생성 요청량·지연·결과 태그·실패 근거 |
| warmup.js, warmup.log, warmup-summary.json, warmup-raw.json, warmup-exit-code.txt | 본 측정과 분리된 워밍업 조건·결과·시간별 표본 |
| warmup-evidence.json, warmup-db.json, purchase-warmup.sql | 실제 구매 성공/거절 수, 판매별 확정·불변식 대조 결과와 SQL |
| warmup-pools-before.json, warmup-pools-after.json | 워밍업 전후 Hikari timeout/프로세스 시작 시각 |
| phases.json | 워밍업/본 측정 경계. 본 측정 미실행 시 loadStart 없음 |
| nginx.conf, prometheus.yml, container-network-map.json | 실제 파일 설정과 upstream IP → 서비스 대응 |
| before/after-containers.txt, resources.jsonl | 전후 상태와 자원 스냅샷 |
| before/after-db.txt, inventory.txt | 불변식·주문·결제 집계, 판매별 재고 |
| prometheus.json | 워밍업부터 종료까지 앱/JVM/HTTP/Hikari 시계열. 실제 scrape/query step 모두 1초 |
| db-waits.sql, db-waits.txt, db-waits-errors.txt, db-waits-status.txt | Diagnostics에서 워밍업 초기 100ms 간격, 최대 450회 DB 세션·대기·차단 PID 표본 |
| pre-warmup/before/after/failed-서비스-cpu-stat.txt | cgroup v2 CPU 누적 사용·throttling 카운터. 전후 차이로 해석 |
| services.log, error.txt(실패 시) | 서비스 로그·중단 이유 |
| collection-errors.txt(수집 실패 시) | 최초 실험 오류와 별개인 후속 수집 오류 |

성공한 k6 종료 후 30초 뒤 최종 상태를 저장한다. 실패하면 기다리지 않고 가능한 진단 자료를 저장한 뒤 종료한다.
수집 중 실패해도 이미 저장한 파일은 유지한다. 모든 실패에서 모든 파일이 생기는 것은 아니다.
`collected`는 수집 완료이며 비즈니스 합격이 아니다. raw.json은 크기가 클 수 있어 Git에서 제외한다.
워밍업 실패도 DB/자원 스냅샷과 Prometheus를 저장한다. services.log는 이번 실행 시작 이후만 포함한다.
Diagnostics의 DB 관측기는 앱 풀과 별개인 연결 하나를 쓰며, 워밍업 종료 또는 실패 시 자기 세션만 종료한다.
최대 450회라는 상한도 있다. 표본 파일은 psql watch 머리글을 포함하므로 `{`로 시작하는 줄이 JSON 표본이다.
부하 발생기 자체의 자원 시계열은 아직 수집하지 않는다. 단계 로그 해석은 [진단 가이드](diagnostics.md)를 본다.

## 5. 결과를 보고 다음 단계 결정

먼저 종료 코드·dropped_iterations로 목표 요청을 실제로 보냈는지 확인한다.
그 뒤 성공/409/429/오류, 지연, CONFIRMED/held/UNKNOWN과 측정 판매의 재고를 함께 본다.
본 측정 후 DB 불변식 쿼리 첫 결과는 0행이어야 한다. 전체 비즈니스 합격 판정은 사람이 확인한다.
워밍업의 판매별 불변식과 확정 완료만 harness가 본 측정 진입 전에 자동 검사한다.

본 측정은 구매 요청 p99 < 1초, 결제 접수 p95 < 1초를 각각 검사한다.
판매 등록과 워밍업의 HTTP 응답은 이 두 지연 판정에 포함하지 않는다.
빠른 거절만으로 개선을 주장하지 않는다. 낮은 요청량 실험이 유효하면 DB 기준선의 요청량을 한 단계씩 올리고,
병목 근거가 생긴 뒤 같은 조건의 gate OFF/ON을 비교한다.
생성기가 막히거나 수집이 불완전하면 서버 최적화보다 측정 조건을 먼저 정리한다.

재시도 폭주·구매 포기·반환 재고 재구매, 장애 주입, API 1개/2개 공정 비교는 후속 실험이다.
기존 세 건짜리 기능 스모크는 `pwsh -NoProfile -File ./ops/smoke.ps1`로 별도 실행할 수 있다.

## 코드 경계

- `src/main`: 서비스 구현과 기본 OFF인 진단 로그. 부하 발생이나 실험 합격 판정은 없다.
- `src/test`: Java 기능/진단 테스트. Run은 bootJar만 빌드하며 Gradle test나 smoke를 호출하지 않는다.
- `ops/performance.ps1`: 준비·기동·초기화·수집·실험 순서 조정.
- `ops/performance/purchase-warmup.*`: 이 구매 실험의 워밍업 결과 대조와 SQL. 일반 DB 초기화/health와 분리.
- `k6/lib/purchase-flow.js`: 공통 HTTP 동작. `warmup.js`와 `purchase-spike.js`는 각자의 도착 패턴·threshold만 정의.
- `artifacts/performance`: 실행 결과. 실행 코드나 테스트 모듈로 import하지 않는다.
