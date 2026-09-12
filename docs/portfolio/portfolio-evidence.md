# 포트폴리오 evidence / claim ledger

기준선: Hikari boundary 보강(`33128be`), 2026-09-12 첫 Warmup 실패와 실제 도착 수 기준 판정까지 반영.
발전 서사는 [portfolio-evolution](portfolio-evolution.md), 현재 성능 계약은 [performance](../performance.md)를 따른다.
**새 Target v1 steady-state 성능은 measurement pending이다. 첫 Warmup은 실행했지만 실패했고, 건수 판정 수정 후 재실행은 대기 중이다.**

- TYPE 1 — MEASURED: 실제 실험 또는 기능 검증에서 확인한 범위만 주장한다. 문제 재현도 포함하며 성능 개선과 구분한다.
- TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT: 설계·구현 사실만 주장한다. offline test 성공은 부하 효과의 실측이 아니다.
- PRIMARY: 이력서 핵심 후보. SUPPORTING: 면접에서 실험의 신뢰성과 구현 판단을 설명할 근거. 억지로 독립 이력서 bullet로 만들지 않는다.
- 숫자는 해당 버전·조건과 함께 사용한다. 과거 Java/Python 결과를 현재 Target 달성값으로 옮기지 않는다.
- 원본 artifact를 문서보다 우선한다. 아래 대조에서 인용한 문서 수치와 원본 간 수치 충돌은 없었으며, 반올림·집계 범위 차이는 각 항목에 적었다.

## 목차

1. TYPE 1: 동시성 재현, 저장소 장애 주입, Front Gate 비교, Java 저부하 flow, 기존 Worker 병목, observer 실패, cold Worker 실패, 기능 계약 검증
2. TYPE 2: Waiting/READY, Reservation 보호, durable payment, Mock PG delay, immediate refill, 단계형 warmup, Isolation, Business, retry/failure 부하, 측정 evidence 개선
3. 사용 금지 주장과 승격 절차

## TYPE 1 — 실제 evidence가 있는 주장

### 1. 초과 판매 재현과 재고 전략 비교

- Status: TYPE 1 — MEASURED
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 단일 hot product의 조회·검사·갱신이 동시 요청에서도 재고를 보존하는지 확인.
- 당시 판단: 오래된 재고 값을 덮어쓰는 lost update를 재현하고 원자적 UPDATE·비관적 락·Redis Lua를 같은 행렬로 비교.
- 변경 내용: naive 경로와 세 대안, 재고/주문 정합성 검사 및 반복 harness 구현.
- Evidence: 과거 commit `0c39efc77bdc1725f665b726570ec1de5d93eab6`의 [v1 원본 실험](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v1-oversell-baseline.md), [v2 원본 CSV](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v2-stock-strategy-comparison.csv).
- 측정 결과: v1 stock 100, 1,000 VU / shared iterations 1,000에서 성공 주문 973, sold 97, oversell 873, order-stock gap 876. v2 기본 행렬은 stock 100, users 100/500/1,000 × 조건별 5회. 세 대안 각각 15행 모두 oversell=0, decision_order_gap=0. 확장 부하 행은 이 15회에 포함하지 않았다.
- 현재 안전하게 사용할 수 있는 문장: “이전 Java 구현에서 재고 100개에 주문 973건이 생성되는 동시성 문제를 재현하고, 세 재고 전략을 각 15회 비교해 기본 행렬의 초과 판매와 재고/주문 불일치 0건을 확인했다.”
- 아직 사용하면 안 되는 문장: “현재 서비스는 모든 동시성·결제 장애에서 정합성을 보장한다.”
- 제한 / caveat: 로컬 Windows Docker Compose, 단일 상품 재고 실험. v1은 단일 실행이고, 결제·저장소 간 장애는 별도 검증 대상이다.
- TYPE 1 승격 조건: 해당 없음. 현재 Target로 범위를 넓히려면 별도 evidence가 필요하다.

### 2. Redis 차감 후 DB 저장 실패 주입

- Status: TYPE 1 — MEASURED
- Portfolio priority: PRIMARY
- 문제 / 요구사항: Redis 원자적 차감 뒤 DB 저장이 실패하면 두 저장소의 상태가 어긋날 수 있음.
- 당시 판단: 정상 처리 지연만으로 전략을 선택할 수 없으므로 저장 직전 예외를 주입.
- 변경 내용: AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE 실패와 저장소 대조 검사 구현.
- Evidence: 같은 과거 commit의 [실험 조건](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v2-stock-failure-injection.md), [원본 CSV](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v2-stock-failure-injection.csv).
- 측정 결과: stock 100, users 500/1,000 × 5회 × 두 전략 = 20행. Redis 10행 모두 차감 100·DB 주문 90·실패 주입 10·gap=-10. RDB 10행은 차감/주문 100·gap=0. oversell은 두 전략 모두 0.
- 현재 안전하게 사용할 수 있는 문장: “장애 주입으로 Redis 내부 원자성이 DB 저장까지의 정합성을 보장하지 않음을 확인하고, 단일 DB 트랜잭션과 별도 보상 설계의 차이를 검증했다.”
- 아직 사용하면 안 되는 문장: “Redis 장애 처리로 성능을 개선했다.” “Redis/DB 장애 복구를 완성했다.”
- 제한 / caveat: 로컬에서 특정 예외 위치를 주입한 실험이다. orphan stock decision이며 초과 판매가 아니다. 프로세스 종료 복구 전체의 검증이 아니다.
- TYPE 1 승격 조건: 해당 없음.

### 3. 이전 v3.2 Front Gate 지연 비교

- Status: TYPE 1 — MEASURED
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 품절·중복·실패 요청까지 RDB 예약 경로에 들어오는 비용.
- 당시 판단: Redis Front Gate의 복잡성을 정당화할 지연 차이가 있는지 RDB atomic과 비교.
- 변경 내용: marker/TTL/finalize/compensation을 가진 Front Gate 및 비교 harness 구현.
- Evidence: [원본 24행 CSV](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v3-2-architecture-vu-baseline.csv), [조건·해석](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v3-2-architecture-load-comparison.md). 실험 직전 코드 `5c15151`.
- 측정 결과: 두 구조 × normal/sold-out/duplicate/failure × users 1,000/3,000/5,000 = 24회, 각 조합 1회. 12쌍에 `(RDB p95 - Redis p95) / RDB p95 × 100` 적용 시 43.4895746~69.5137293%, 문서의 43.5~69.5%와 반올림 일치. 24행 oversell 및 decision_reservation_gap 모두 0.
- 현재 안전하게 사용할 수 있는 문장: “이전 Java v3.2의 로컬 Waiting OFF 비교에서 Redis Front Gate의 HTTP p95가 RDB 기준보다 조건별 43.5~69.5% 낮았으며, 24회 실행의 초과 판매·예약 불일치는 0건이었다.”
- 아직 사용하면 안 되는 문장: “현재 Target v1 성능을 69.5% 개선했다.” “대기실로 DB 부하를 69.5% 줄였다.”
- 제한 / caveat: VU/shared-iterations, Waiting OFF, pool 10, connection timeout 30,000ms. normal/failure stock 100, sold-out 0, duplicate max(100, users). generator와 서버가 같은 로컬 호스트다. 조건별 반복·신뢰구간이 없고 pool sweep은 별도 실험이다.
- TYPE 1 승격 조건: 해당 없음. 일반화된 성능 claim에는 분리 환경·반복 및 arrival-rate 비교가 필요하다.

### 4. Java baseline 저부하 purchase/payment flow

- Status: TYPE 1 — MEASURED
- Portfolio priority: SUPPORTING
- 문제 / 요구사항: 구매 성공부터 결제 접수·최종 확정까지 전체 흐름이 이어지는지 확인.
- 당시 판단: 예열과 본 측정을 분리하고 실제 성공 수를 DB와 대조.
- 변경 내용: baseline warmup 및 측정 evidence 수집. 실행 코드 `bcfebec`.
- Evidence: [실행 분석](../../artifacts/performance/20260909-204228-733-baseline-purchase-spike/review.md), [집계와 raw SHA-256](../../artifacts/performance/20260909-204228-733-baseline-purchase-spike/measured-metrics.json), [최종 재고](../../artifacts/performance/20260909-204228-733-baseline-purchase-spike/after-inventory.txt), [DB](../../artifacts/performance/20260909-204228-733-baseline-purchase-spike/after-db.txt).
- 측정 결과: warmup 10 RPS/30초 301건 후 본 측정 10 RPS/60초 602건 구매·접수·확정. purchase p99=17.75859996ms, payment acceptance p95=9.59478485ms. 오류/dropped=0. 본 측정 판매 sold=602/held=0, 전체 DB 903건은 warmup 301건을 포함한다.
- 현재 안전하게 사용할 수 있는 문장: “Java baseline의 예열 후 10 RPS/60초 단일 실행에서 602건 모두 확정하고 구매 p99 17.76ms, 결제 접수 p95 9.59ms를 확인했다.”
- 아직 사용하면 안 되는 문장: “최대 capacity를 입증했다.” “예열만으로 지연을 X% 개선했다.”
- 제한 / caveat: baseline/Gate OFF, Diagnostics ON, 로컬 1회. p99 원시 자료는 로컬 보존이며 Git에는 계산 방법·해시·집계가 있다. 현재 Target warmup 계약과 다르다.
- TYPE 1 승격 조건: 해당 없음.

### 5. 기존 Worker의 구조적 공급 병목 발견

- Status: TYPE 1 — MEASURED
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 빠른 HTTP 접수가 판매 확정 용량을 의미하는지 확인.
- 당시 판단: 접수 지연과 비동기 backlog·확정 시계열을 분리하고 dispatch 코드와 대조.
- 변경 내용: capacity 검토에 pending·oldest age·최종 확정을 포함. 이후 immediate refill 구현 효과는 TYPE 2로 분리한다.
- Evidence: [실행 조건](../../artifacts/performance/20260909-230854-201-baseline-capacity/run.json), [결과](../../artifacts/performance/20260909-230854-201-baseline-capacity/capacity-result.json), [HTTP summary](../../artifacts/performance/20260909-230854-201-baseline-capacity/summary.json), [시계열](../../artifacts/performance/20260909-230854-201-baseline-capacity/order-progress.txt), [당시 Worker](https://github.com/tldnr1/limited-goods-reservation/blob/76183486371c2ce60eab98d173c9c9dad67ded8d/src/main/java/com/limitedgoods/worker/Worker.java).
- 측정 결과: Gate OFF, stock 10,000, warmup 10/s·30초 후 40/s·60초 공급. acceptance 2,401, payment p95=9.476324ms, backlog max=1,473. 유효 첫 표본 14:12:50.275474Z의 confirmed=4와 마지막 14:14:20.274138Z의 1,392로 `(1392-4)/89.998664=15.422451/s`. 이후 최종 DB 검사 confirmed=1,424이며 전체 통과하지 않았다.
- 현재 안전하게 사용할 수 있는 문장: “40/s 공급에서 빠른 HTTP 접수와 비동기 확정 처리량이 별개임을 확인했고, 약 15.4/s 시계열을 최대 4건·fixed-delay 250ms의 약 16/s 제출 상한과 연결했다.”
- 아직 사용하면 안 되는 문장: “Worker가 40/s를 달성했다.” “처리량을 2.5배 개선했다.”
- 제한 / caveat: 약 90초 기울기는 공급+drain 관측이다. 16/s는 이론적 제출 상한이지 측정값이 아니다. 검사 시점 나머지 주문을 영구 실패라고 단정하지 않는다. 새 PG delay/SLO와 직접 전후 비교 불가.
- TYPE 1 승격 조건: 해당 없음. 개선 결과는 아래 13번 조건으로 별도 승격한다.

### 6. PowerShell observer 실행 경계 오류

- Status: TYPE 1 — MEASURED
- Portfolio priority: SUPPORTING
- 문제 / 요구사항: 실제 Start-Job에서 observer가 `D:\target-state.sql`을 찾아 startup 실패.
- 당시 판단: mock/offline 호출만으로 스크립트 파일 문맥의 차이를 잡지 못했다.
- 변경 내용: `1f23e63`에서 absolute script path를 자식 프로세스에서 호출하도록 수정하고 observer process 회귀 검사 추가.
- Evidence: [원본 error](../../artifacts/performance/20260911-232830-531-target-worker-normal/error.txt), [observer error](../../artifacts/performance/20260911-232830-531-target-worker-normal/observer-error.txt), [수정 commit](https://github.com/tldnr1/limited-goods-reservation/commit/1f23e63), [observer test](../../ops/performance/target-observer-test.ps1). 원본 보관 commit `e217daa`.
- 측정 결과: 1회 harness integration 실패, k6 본 측정 전 중단. 서비스 성능 측정값 없음.
- 현재 안전하게 사용할 수 있는 문장: “실제 PowerShell Job 경계에서만 발생한 observer 경로 오류를 찾아 절대 경로 호출과 자식 프로세스 회귀 검사로 보강했다.”
- 아직 사용하면 안 되는 문장: “서비스 성능 병목을 해결했다.”
- 제한 / caveat: 자식 프로세스 테스트의 Docker는 mock이다. 전체 real load harness 통합 성공을 의미하지 않는다.
- TYPE 1 승격 조건: 해당 없음.

### 7. cold/no-warmup Target Worker 실행의 초기 오류 발견

- Status: TYPE 1 — MEASURED
- Portfolio priority: SUPPORTING — warmup 후 동일 Worker 조건의 측정과 연결되면 전후 개선 사례로 PRIMARY 승격 검토.
- 문제 / 요구사항: 기동 직후 측정을 steady-state capacity로 해석하면 초기 오류가 섞임.
- 당시 판단: Payment 실패와 Worker 완료를 분리하고 오류 발생 시점을 확인.
- 변경 내용: `d1b4b50`에서 DB JSONL framing 및 HTTP/DB 불일치 진단 수정, 원본을 보존한 별도 재분석. 이후 warmup 효과는 TYPE 2다.
- Evidence: [config](../../artifacts/performance/20260911-234502-053-target-worker-normal/config.json), [k6 summary](../../artifacts/performance/20260911-234502-053-target-worker-normal/k6-summary.json), [Prometheus](../../artifacts/performance/20260911-234502-053-target-worker-normal/prometheus.json), [DB](../../artifacts/performance/20260911-234502-053-target-worker-normal/after-db.json), [분석·원본 해시](../../artifacts/target-v1/20260911-worker-diagnosis/review.md), [source.json](../../artifacts/target-v1/20260911-worker-diagnosis/source.json).
- 측정 결과: Worker normal 10 RPS/30초, VUs 10/MaxVUs 50, reset=true, embedded warmup 없음. 288 iterations, dropped=13, 결제 HTTP 292회 중 202=228/500=60/client timeout=4. Payment Hikari 0→60. DB attempts/succeeded/confirmed=232, pending/failed=0. 전체 시도 p95=2032.06228135ms이며 accepted-only 수치가 아니다.
- 현재 안전하게 사용할 수 있는 문장: “기동 직후 Payment path의 초기 오류를 관측하고 cold-start와 steady-state capacity 측정을 분리할 필요를 확인했다.”
- 아직 사용하면 안 되는 문장: “Worker는 10 RPS도 처리하지 못한다.” “단계형 warmup으로 문제가 해결됐다.”
- 제한 / caveat: 로컬 raw.json의 target_latency를 summary.setup_data.startedAt 기준 응답 완료 시각으로 재집계하면 500은 +3.488~9.686초, timeout은 +5.001~5.501초. +10초 이후 202는 207건, 선형 보간 p95=188.9275967ms. 사후 구간 분리는 전체 실패를 PASS로 바꾸지 않는다. raw는 로컬 보존, Git에는 기존 구간 분석이 있고 source.json 해시는 DB 표본용이다. timeout 요청 4건도 DB에서 확정되어 232와 228은 모순이 아니다. 풀 부족을 근본 원인으로 확정하지 않는다.
- TYPE 1 승격 조건: 해당 없음. 새 warmup PASS/동일 조건 측정 전에는 해결·개선 claim 불가. legacy result.json의 “Worker 32/s required, 40/s normal target”은 당시 snapshot이며 현재 계약이 아니다.

### 8. Target 기능 계약 검증

- Status: TYPE 1 — MEASURED
- Portfolio priority: SUPPORTING
- 문제 / 요구사항: 점유·결제·멱등·응답 유실·UNKNOWN의 상태 계약을 성능과 별도로 확인.
- 당시 판단: 실제 PostgreSQL/Redis와 제어 시계 기능 검사 및 소량 HTTP smoke가 필요.
- 변경 내용: Target 역할과 상태 계약 구현, READY 만료/유실 응답/지연 성공 등 기능 검사.
- Evidence: [39 tests 집계](../../artifacts/target-v1/20260910-functional/tests.json), [smoke](../../artifacts/target-v1/20260910-functional/smoke.json), [검증 범위](../../artifacts/target-v1/20260910-functional/review.md), [ContractTest](../../src/test/java/com/limitedgoods/ContractTest.java).
- 측정 결과: 당시 39 tests, failures/errors=0. smoke 4건 CONFIRMED, sold=4/held=0/available=0. 미사용 READY는 실제 11초 후 EXPIRED, ghost hold=0.
- 현재 안전하게 사용할 수 있는 문장: “실제 DB 기반 기능 테스트와 소량 smoke로 점유·결제 상태 계약을 검증했다.”
- 아직 사용하면 안 되는 문장: “부하·장애 상황에서도 복구 시간과 성능을 보장한다.”
- 제한 / caveat: 당시 기록이며 이번에 Java/DB 테스트를 재실행하지 않았다. 300초 및 299초 경계는 제어 시계 검사이고 실제 5분 대기는 미실행. 당시 문서의 32/s 목표는 폐기됐다.
- TYPE 1 승격 조건: 해당 없음. 부하/failure capacity는 17번에 별도 기록한다.

## TYPE 2 — 구현 완료, 효과 실측 대기

아래 항목의 설계 효과와 새 steady-state 성능은 **검증 대기**다. 실패 실행이나 일부 계측의 동작 확인이 전체 효과를 증명하지는 않는다.

### 9. Waiting / READY admission

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 대량 유입이 재고 DB 트랜잭션으로 직접 전달되는 것을 제어.
- 당시 판단: 진입권과 재고 원장을 분리하고 downstream 공급률을 제한.
- 변경 내용: DB connection을 사용하지 않는 Waiting role, Redis WAITING/READY, WaitingRate. READY는 permission이며 stock hold가 아니다. PostgreSQL authoritative inventory 유지.
- Evidence: [WaitingService](../../src/main/java/com/limitedgoods/waiting/WaitingService.java), [Waiting profile](../../src/main/resources/application-waiting.yml), [Target 계약](../architecture/target-v1.md), 구현 `4256c69`.
- 측정 결과: 미측정.
- 현재 안전하게 사용할 수 있는 문장: “Redis 기반 Waiting/READY 계층을 분리하고 READY를 재고 점유가 아닌 제한된 구매 진입권으로 설계했다.”
- 아직 사용하면 안 되는 문장: “50,000명 유입을 안정적으로 처리했다.” “DB 부하를 X% 줄였다.”
- 제한 / caveat: Waiting 무DB는 역할 경계이며 전체 시스템의 DB 사용 제거가 아니다.
- TYPE 1 승격 조건: embedded warmup PASS, Waiting 대표 조건 3회, join/poll p99·오류·dropped 계약, DB connection=0·durable order/payment=0, Redis 자원 안정 evidence 확보.

### 10. Reservation 보호

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 재고 hot-row 경로의 유입 및 동시 점유를 제한.
- 당시 판단: Redis gate는 DB 보호, PostgreSQL 트랜잭션은 authoritative hold를 담당.
- 변경 내용: ReservationRate/permits, 짧은 critical section, 새 주문 응답 재조회 3개 제거.
- Evidence: [AdmissionGate](../../src/main/java/com/limitedgoods/admission/AdmissionGate.java), [PurchaseTransactionService](../../src/main/java/com/limitedgoods/purchases/PurchaseTransactionService.java), [설계](../../DESIGN.md).
- 측정 결과: 미측정.
- 현재 안전하게 사용할 수 있는 문장: “구매 진입률과 동시 실행 수를 제한하고 DB 재고 트랜잭션의 임계 구간을 줄이도록 구현했다.”
- 아직 사용하면 안 되는 문장: “25/s capacity를 검증했다.” “락 병목을 해결했다.”
- 제한 / caveat: 25/s·permits 8은 hypothesis/policy이며 capacity SLO가 아니다.
- TYPE 1 승격 조건: 동일 자원·workload에서 warmup 후 Reservation 반복, 실제 READY→201 p99, Hikari delta=0, 정합성/누락 및 DB lock/자원 evidence. 개선율은 같은 조건 전후 비교 필요.

### 11. Payment durable acceptance와 async Worker

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: PRIMARY
- 문제 / 요구사항: PG 응답 지연이 사용자 접수 HTTP 연결을 직접 점유하지 않게 분리.
- 당시 판단: 접수와 성공은 다른 상태이며 accepted SUCCESS의 deadline까지 관측해야 함.
- 변경 내용: DB 저장 후 202, Worker에서 PG 호출, terminal_at 및 confirmationDeadline evidence.
- Evidence: [PaymentService](../../src/main/java/com/limitedgoods/payments/PaymentService.java), [Worker](../../src/main/java/com/limitedgoods/worker/Worker.java), [timeline SQL](../../ops/performance/target-timeline.sql), evidence 보강 `8797ec5`.
- 측정 결과: 미측정. baseline의 무지연 저부하 성공을 새 PG 지연 효과로 사용하지 않는다.
- 현재 안전하게 사용할 수 있는 문장: “결제를 DB에 영속 접수한 뒤 외부 PG 처리를 Worker로 분리해 PG 대기를 결제 접수 요청과 분리했다.”
- 아직 사용하면 안 되는 문장: “PG 200ms 지연에서도 성능 저하 없이 처리했다.”
- 제한 / caveat: DB·CPU는 공유한다. 202는 결제 성공이 아니고 Worker 슬롯에는 PG 대기가 남는다.
- TYPE 1 승격 조건: 명시된 PG delay와 자원에서 accepted-only p95·오류, backlog/oldest age, accepted SUCCESS별 deadline와 drain 후 pending=0 실측.

### 12. configurable Mock PG delay

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: SUPPORTING
- 문제 / 요구사항: 외부 응답 지연의 영향을 재현할 실험 장치 필요.
- 당시 판단: durable receipt commit 이후에 지연시켜 DB 트랜잭션 내 sleep과 구분.
- 변경 내용: MOCK_PG_DELAY_MS 0~5000ms, store transaction 반환 뒤 response delay. 기본값은 변경하지 않았다.
- Evidence: [MockPaymentController](../../src/main/java/com/limitedgoods/mockpg/MockPaymentController.java), [MockPaymentStore](../../src/main/java/com/limitedgoods/mockpg/MockPaymentStore.java), 구현 `8797ec5`.
- 측정 결과: 미측정.
- 현재 안전하게 사용할 수 있는 문장: “Mock PG가 영속 처리 후 트랜잭션 밖에서 응답 지연을 주도록 구현해 후속 지연 실험을 준비했다.”
- 아직 사용하면 안 되는 문장: “PG 지연에서도 안정성을 입증했다.”
- 제한 / caveat: 실제 외부 PG나 물리적으로 독립된 저장소를 재현하지 않는다.
- TYPE 1 승격 조건: MockPgDelayMs=200 등 명시 조건에서 accepted latency/backlog/oldest pending/deadline 위반/자원을 반복 측정.

### 13. Worker immediate refill

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 기존 dispatch당 최대 4건·250ms가 만든 공급 상한.
- 당시 판단: 작업이 남아 있으면 빈 슬롯이 다음 schedule을 기다릴 필요가 없음.
- 변경 내용: 각 슬롯이 claim→process→next claim을 반복하고 scheduler는 빈 슬롯 refill/빈 큐 재확인 담당.
- Evidence: [현재 Worker](../../src/main/java/com/limitedgoods/worker/Worker.java), [WorkerTest](../../src/test/java/com/limitedgoods/WorkerTest.java), [구현 당시 기능 기록](../../artifacts/target-v1/20260910-functional/review.md), `4256c69`.
- 측정 결과: 미측정. 기존 15.422451/s 관측은 5번의 변경 전 결과다.
- 현재 안전하게 사용할 수 있는 문장: “Worker 슬롯이 완료 직후 다음 작업을 가져오도록 바꿔 코드상 4건/250ms 제출 상한을 제거했다.”
- 아직 사용하면 안 되는 문장: “Worker 40/s 달성.” “처리량 X→Y 개선.”
- 제한 / caveat: 다른 병목·PG 지연·CPU/DB 한계가 남을 수 있다. 단일 dispatch 연속 처리 회귀 검사는 capacity 측정이 아니다.
- TYPE 1 승격 조건: embedded warmup PASS, 동일 resource envelope/PG/workload, Worker probe 3회, successDeadlineViolations=0·backlog 회복. 전후 공급+drain 구간과 관측 의미를 맞춰 비교 가능할 때만 X→Y 작성.

### 14. 단계형 Warmup Validation

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: SUPPORTING
- 문제 / 요구사항: cold-start 초기 오류와 steady-state 측정이 혼합됨.
- 당시 판단: 단순 대기 대신 전체 요청 경로의 안전성·영속 완료를 검증.
- 변경 내용: 2→5→10→10 RPS 각 10초, 270 users. Waiting→Reservation→Payment→Worker→Mock PG→CONFIRMED. embedded warmup PASS 후 데이터 cleanup, 동일 JVM의 measured trial. 각 trial의 Hikari boundary도 독립 저장.
- Evidence: [warmup.js](../../k6/target/warmup.js), [orchestration](../../ops/performance/target.ps1), [cleanup](../../ops/performance/target-warmup-cleanup.ps1), [warmup test](../../ops/performance/target-warmup-test.ps1), `8797ec5` 및 boundary 보강 `33128be`.
- 측정 결과: 초기 오류 해소 효과는 검증 대기. [첫 Stage 0](../reviews/warmup-20260912-review.md)은 실제 272회 도착·완료, Payment Hikari delta=18로 실패했다. 고정 270건 판정은 수정했으나 전체 실제 도착의 결제 성공과 warmup PASS는 아직 없다.
- 현재 안전하게 사용할 수 있는 문장: “전체 구매·결제 경로를 단계형으로 예열하고 안전성 검증 후 같은 JVM에서 본 측정을 시작하도록 구현했다.”
- 아직 사용하면 안 되는 문장: “warmup으로 초기 오류를 해결했다.”
- 제한 / caveat: cold 결과도 별도 보존한다. JVM을 재기동해도 DB/OS 캐시는 남을 수 있다.
- TYPE 1 승격 조건: 새 실제 Warmup Validation의 계획 arrivals=270(경계 추가 허용 270~274), dropped/unexpected=0, 실제 시작 수 전체의 purchase/payment durable 연결과 CONFIRMED, pending/correctness/Hikari delta/restart/OOM=0. 먼저 Stage 0 안전성만 승격하고 steady-state 성능은 별도 측정.

### 15. Isolation 비교

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: PRIMARY
- 문제 / 요구사항: Waiting 폭주가 기존 결제 경로에 주는 영향 확인.
- 당시 판단: 동일 Payment 공급에 Waiting만 추가한 비교가 필요.
- 변경 내용: Worker-only와 Isolation 시나리오 및 역할별 자원/pool 분리.
- Evidence: [isolation.js](../../k6/target/isolation.js), [worker.js](../../k6/target/worker.js), [Target compose](../../compose.target.yml), [시나리오](../architecture/target-v1-load-scenario.md).
- 측정 결과: 미측정.
- 현재 안전하게 사용할 수 있는 문장: “Waiting 유입을 추가했을 때 Payment 경로가 유지되는지 동일 결제 workload로 비교하도록 구성했다.”
- 아직 사용하면 안 되는 문장: “Isolation으로 성능 저하를 X% 이하로 제한했다.”
- 제한 / caveat: 역할별 프로세스/pool 분리는 물리 자원 독립성이 아니다. 상대 degradation의 임의 합격 한도는 없다.
- TYPE 1 승격 조건: 같은 Payment workload/resource/warmup의 Worker-only vs Isolation 반복, accepted p95·backlog/deadline·Hikari·오류·correctness 비교 evidence.

### 16. Business workload / SLO

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 순간 관심 유입과 실제 판매 완료를 함께 평가.
- 당시 판단: 관심 사용자 수·stock hold·PG 확정은 서로 다른 지표.
- 변경 내용: 50,000 interested users, stock 1,000; 0~5초 30k, 5~15초 15k, 15~60초 5k. saleStart+60초 initial HELD, +120초 ≥95% CONFIRMED 목표.
- Evidence: [business.js](../../k6/target/business.js), [review](../../ops/performance/target-review.ps1), [현재 성능 계약](../performance.md), `edd2044`/`8797ec5`.
- 측정 결과: 미측정.
- 현재 안전하게 사용할 수 있는 문장: “관심 사용자 50,000명과 재고 1,000개를 가정하고 유입·초기 점유·판매 확정의 시간 기준을 나눠 검증 workload를 설계했다.”
- 아직 사용하면 안 되는 문장: “50,000명 동시 처리 성공.” “50,000 RPS 처리.” “새 Target 목표 달성.”
- 제한 / caveat: 모두 가정/목표다. 모든 사용자 구매 성공·1초 내 READY·60초 내 전체 사용자 결과를 약속하지 않는다.
- TYPE 1 승격 조건: 최종 generator/server 분리, fixed/declared resource envelope, 50k/stock1000 대표 조건 3회, saleStart+60/+120·정합성·accepted latency·deadline·Hikari·오류·dropped evidence.

### 17. Retry / failure 부하와 복구 효과

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: PRIMARY
- 문제 / 요구사항: 재시도·응답 유실·UNKNOWN을 중복 성공이나 잘못된 재고 반환으로 만들지 않아야 함.
- 당시 판단: 동일 사용자/멱등키 replay, 같은 attempt 재확인, 미확정 재고 보유가 필요.
- 변경 내용: LOST_RESPONSE, UNKNOWN, FAILED, delayed success, duplicate success 방어 및 accepted payment의 time-only stock release 방지. retry 정책은 이번에 확장하지 않았다.
- Evidence: [PaymentService](../../src/main/java/com/limitedgoods/payments/PaymentService.java), [ReservationService](../../src/main/java/com/limitedgoods/reservations/ReservationService.java), [ContractTest](../../src/test/java/com/limitedgoods/ContractTest.java), [기능 기록](../../artifacts/target-v1/20260910-functional/review.md).
- 측정 결과: 부하/failure capacity는 미측정. 기능 계약 검증은 8번 TYPE 1의 제한된 범위다.
- 현재 안전하게 사용할 수 있는 문장: “결제 시도를 영속화하고 유실 응답을 같은 attempt로 재확인하며 UNKNOWN의 재고를 시간만으로 반환하지 않도록 구현했다.”
- 아직 사용하면 안 되는 문장: “장애 복구 시간을 X% 단축했다.” “모든 장애에서 자동 복구한다.”
- 제한 / caveat: 영구 UNKNOWN 수동 복구와 실제 외부 PG 운영은 범위 밖이다.
- TYPE 1 승격 조건: 선언된 retry/failure workload 반복, 중복 주문/attempt/성공=0, durable state·복구/재확인·backlog·시간 evidence. 영구 UNKNOWN은 정상 판매 완료 목표로 판정하지 않는다.

### 18. Hikari boundary와 측정 evidence 보강

- Status: TYPE 2 — IMPLEMENTED / PENDING MEASUREMENT
- Portfolio priority: SUPPORTING
- 문제 / 요구사항: range 첫 scrape 전 timeout을 놓치거나 실패 HTTP가 성공 접수 지연에 섞이는 관측 오류.
- 당시 판단: 명시적 경계와 성공 응답만의 지연, terminal 시각으로 판정 근거를 고정해야 함.
- 변경 내용: 각 trial의 boundary-before/after, stable instance+pool key, freshness·누락·중복·reset 검사. Prometheus instant query와 timestamp()를 같은 평가에서 수집하고 15회 × 요청 timeout 2초 + 사이 1초 대기로 제한한다. after는 bounded drain 이후 scrape를 요구한다. range evidence는 진단용으로 유지한다. 기존 accepted-only/terminal_at/saleStart 보강도 구현 근거다.
- Evidence: [Hikari helper](../../ops/performance/target-hikari.ps1), [trial](../../ops/performance/target-trial.ps1), [review test](../../ops/performance/target-review-test.ps1), [collection test](../../ops/performance/target-hikari-test.ps1), [static test](../../ops/performance/target-static-test.mjs). 기존 보강 `8797ec5`, boundary 보강은 `33128be`.
- 측정 결과: [첫 Warmup](../reviews/warmup-20260912-review.md)에서 실제 boundary 수집과 Payment delta=18 검출을 확인했다. offline에서는 flat range 증가 검출·누적 3→3 허용·감소/불일치 실패·baseline 실패 시 load 차단을 검증했다. 성공 warmup 이후 measurement까지 전체 절차의 실측 통과는 대기 중이다.
- 현재 안전하게 사용할 수 있는 문장: “각 trial 경계의 timeout counter 차이를 authoritative evidence로 사용하도록 바꾸고 첫 scrape 이전 증가를 놓치는 판정 오류를 offline 회귀 검사로 막았다.”
- 아직 사용하면 안 되는 문장: “새 warmup/성능 실험에서 Hikari timeout 0을 달성했다.”
- 제한 / caveat: 실제 실패 실행의 수집은 확인했지만 성공 warmup 이후 측정 경로는 아직 검증하지 못했다. 같은 pool/instance로 reset 후 counter가 다시 증가해 최종값이 같아지는 경우는 counter 두 값만으로 증명할 수 없으므로 기존 process/container evidence도 유지한다.
- TYPE 1 승격 조건: 실제 standalone/embedded warmup에서 네 DB 역할의 fresh boundary, 실패 시 측정 차단, cleanup 이후 별도 baseline 및 전체 artifact 수집 확인. 성능 claim은 해당 시나리오의 별도 승격 조건을 충족해야 한다.

## 사용 금지 주장과 승격 절차

현재 사용 금지: “50,000명 동시 처리 성공”, “50,000 RPS”, “10,000 RPS 처리”, “Worker 40/s 달성”,
“PG 200ms 지연에서도 안정적 처리”, “Waiting으로 DB 부하 X% 감소”, “Isolation으로 결제 성능 저하 X% 이하”,
“반환 재고 5초 내 재판매 보장”, “AWS 환경 검증 완료”, “새 Target 성능 목표 달성”.

반환 재고 5초는 secondary/stretch 목표다. hold expiry→release commit→AVAILABLE projection→READY→새 HELD의
정확한 event timestamp가 필요하며 현재 coarse observer 표본만으로 인증하지 않는다.

승격할 때 이 파일의 해당 항목에 실행 commit/JAR·artifact·환경·warmup/reset·workload·반복별 수치·계산 방법을 추가한다.
실패 실행도 보존하고 TYPE 1 범위를 통과한 조건으로 한정한다. 로컬 병목 발견은 로컬 관측 claim으로만 쓰며,
최종 성능 claim은 generator/server 분리·선언된 고정 자원·동일 workload·대표 조건 반복을 요구한다.
이전 버전의 수치와 새 Target 결과를 직접 연결한 개선율은 조건과 지표 의미가 같다는 evidence 없이 작성하지 않는다.
