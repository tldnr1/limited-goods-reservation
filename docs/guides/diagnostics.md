# 초기 지연을 구분하는 방법

이 문서는 기존 **baseline harness**의 Diagnostics 절차다. Target의 역할별 자원·단계형 warmup과
혼용하지 않는다. 현재 실행법은 [Target 가이드](target-v1-load-guide.md), 최근 Payment 초기 오류는
[2026-09-12 분석](../reviews/warmup-20260912-review.md)을 따른다.

AdmissionGate OFF, 구매 10 RPS/30초, VU 20, API별 Hikari 8/Tomcat 40, timeout 1초를 유지한다.
Run -Diagnostics를 선택하면 구매별 단계 로그와 워밍업·본 측정 DB 관측기를 켠다. Check는 읽기 전용이다.
이 옵션은 원인 진단용이다. 일반 capacity 탐색은 OFF로 수행하고, 경계 발견 후 같은 조건 또는 바로 아래 조건을
ON으로 재현한다. 로그와 관측 SQL도 자원을 쓰므로 ON/OFF의 성능값을 같은 측정 조건으로 비교하지 않는다.
일반 Run에서도 nginx 요청 로그, 1초 Prometheus, 워밍업 raw와 실패 자료는 저장한다.
capacity는 추가로 1초 간격 Worker 진행 집계를 수행한다. 이 관측 비용도 0은 아니다.

## 이번 Diagnostics 변경의 의미

측정된 지연을 보정하거나 진단 비용을 숫자에서 빼는 기능은 없다.
기존에는 상세 DB 대기 표본이 워밍업에만 있어 본 측정 병목을 DB 상태와 대조하기 어려웠다.
이제 본 측정 시작 전에도 관측기를 준비하고 본 측정·drain까지 `measured-db-waits.txt`에 기록한다.
워밍업은 기존 `db-waits.txt`를 유지한다. 각각 100ms 목표 간격이며 횟수 상한과 종료 처리가 있다.
capacity의 `order-progress.txt`는 이 상세 진단과 별개다. 부하 중 미처리 결제/가장 오래된 작업의 나이,
주문과 확정 수 추이를 기록해 Worker가 유입을 따라오는지 확인한다. 대기 나이는 결제 완료 지연의 p95/p99가 아니다.
JFR, AdmissionGate 활성화, pool/thread/락 구조 변경은 이번 변경에 포함하지 않았다.

## 워밍업의 목적과 판정

워밍업은 실제 구매/결제 경로의 first-use 비용을 본 측정 전에 흡수하는 단계다.
초기 지연을 정상 상태의 응답 SLO 실패로 판정하지 않는다. 오류/누락 0, Hikari timeout 증가 0,
판매별 정합성과 실제 성공 건수만큼의 주문·점유·결제 확정을 본 측정 진입 조건으로 둔다.
성공 건수를 300으로 고정하지 않으며 409/429는 별도 기록한다. 후반부의 안정화 여부는 분석에서 확인한다.
새 인스턴스 투입 직후의 응답 SLO는 별도의 cold-start/deployment readiness 실험 대상이다.

1.29초 구간의 정확한 원인 규명을 본 측정의 선행 조건으로 두지 않는다.
짧은 JFR은 향후 선택 진단이며 이번 harness에는 추가하지 않았다. 코드/풀/락 조건을 유지한 본 측정을 우선한다.

## 구매 단계 로그

`purchase_timing`은 PurchaseService가 트랜잭션 프록시를 호출하기 직전부터 반환/예외까지 측정한다.
사용자·멱등키·본문은 남기지 않는다. nginx가 덮어써 전달하는 request_id로 HTTP 로그와 구매 로그를 연결한다.
판매/주문 ID, 컨테이너 로그 접두사, 시각도 함께 남긴다. nginx를 거치지 않은 직접 호출은 request_id가 없을 수 있다.
나노초 단조 시계를 사용하며, 출력은 커밋/롤백 후 한 번만 한다. 락 안에서는 시간과 누적값만 기록한다.

| stages_ms 항목 | 포함하는 일 |
|---|---|
| transaction_entry | 프록시 진입~메서드 본문 시작. 트랜잭션 시작/연결 획득 비용이 포함될 수 있음 |
| request_preparation | fingerprint, 시각 준비 |
| idempotency_lock_query | advisory lock SQL 호출 전체 |
| idempotency_lookup | 기존 주문 조회 |
| sale_lookup_and_cache | 판매 조회/검증, gate OFF 시 캐시 접근은 생략 |
| inventory_lock_query | 재고 락 SQL 호출~결과 객체 반환. 순수 락 대기 시간이 아님 |
| stock_validation / user_limit_query | 재고 검증과 사용자별 합산 쿼리. 다중 상품은 항목별 구간을 합산 |
| order_persist / items_persist_and_stock_changes / reservation_persist | persist 호출과 영속 객체 변경. 실제 SQL 발행 시간과 다를 수 있음 |
| response_queries_and_auto_flush | DTO를 만드는 조회. Hibernate가 이때 자동 flush할 수 있음 |
| transaction_completion | 메서드 반환 이후 프록시의 flush/commit/연결 정리 |

강제 flush/추가 비즈니스 SQL/트랜잭션 경계 변경은 하지 않는다.
`after_lock_until_proxy_exit_ms`는 모든 재고 락 조회 반환부터 프록시 종료까지다.
정확한 DB 락 보유 시간이 아니다. 여러 행 중 첫 락의 획득 시점과 커밋 시 실제 해제 시점을 알 수 없다.
실패하면 final_phase와 예외 종류를 남긴다. 실패한 단계에는 뒤따르는 롤백/정리 시간도 포함된다.
transaction_entry에서 실패하면 재고 락 단계에 도달하지 않은 것이다.
지연 연결 획득을 사용하는 환경에서는 첫 SQL 단계에도 연결 획득 비용이 포함될 수 있어 Hikari와 함께 본다.

## DB·HTTP·자원과 대조

- db-waits: application_name으로 api1/api2/worker/mock-pg 구분. wait_type=Lock, wait_event,
  blockers PID와 해당 PID의 세션을 대조한다. transaction_started는 트랜잭션 시작이지 락 획득 시각이 아니다.
  100ms는 목표 표본 간격이며 실행 지연/짧은 대기를 놓칠 수 있다. SQL 앞 500자는 로컬 합성 데이터 진단용이다.
- nginx: msec(요청 종료 시각), URI, upstream IP, 상태, connect/header/response/request 시간을 남긴다.
  request 시작 시각은 msec-request_time으로 근사한다. 초기 0~5초의 **시작/종료 기준**을 구분한다.
  IP는 container-network-map.json과 매핑한다. URI와 상태별로 나눠 구매/결제의 분포를 확인한다.
- Prometheus: 실제 1초 scrape로 Hikari active/pending/timeout, HTTP uri/instance, JVM/GC를 수집한다.
  1초보다 짧은 포화는 여전히 놓칠 수 있다. 연결 획득 실패 로그와 함께 본다.
- cpu.stat: nr_throttled/throttled_usec 증가로 quota 제한의 발생 여부를 확인한다.
  전후 카운터만으로 정확히 어느 요청이 느려졌는지는 특정할 수 없다.

## 다음 판단

1. inventory_lock_query가 길고 Lock/blockers 표본이 있으면 경합 근거다. blocker가 API인지 Worker인지 본다.
2. 락을 얻은 뒤 어느 단계가 긴지 본다. 특정 쿼리/자동 flush/commit이 느리면 그 경로를 먼저 검토한다.
3. 느린 선행 트랜잭션과 뒤 요청의 락 대기는 함께 발생할 수 있다. 둘 중 하나만 원인으로 고르지 않는다.
4. transaction_entry/Hikari가 길어도 재고 락 경합으로 단정하지 않는다. DB 표본·API 편향·CPU와 대조한다.

단계 로그는 구매 경로에 추가했다. Worker/결제의 개별 Java 단계와 SQL별 실행 시간 전부를 측정하는 것은 아니다.
DB 표본에서 이 경로가 blocker로 확인되면 필요한 구간을 추가 계측한다.
첫 실패 자료는 artifacts/performance/20260909-011321-469-baseline-purchase-spike/review.md에 보존한다.

## API 배정 변경

계측 보강 커밋 fa3103f는 기존 round-robin을 유지한다. 다음 커밋에서 `random two least_conn`만 별도 적용했다.
일반 least_conn도 동률이면 round-robin을 쓰므로, 요청이 겹치지 않는 낮은 부하에서 구매→결제의 반복 배정이 남을 수 있다.
random two는 후보 선택을 무작위화한 뒤 활성 연결 수로 비교한다. API 두 대에서는 둘을 비교하지만
고정된 요청 순서에 의존하는 배정을 줄이려는 목적이 있다. 작은 표본의 균등 분포를 보장하지 않는다.
Nginx의 연결 수는 DB 풀/쿼리 비용을 의미하지 않는다. DB hot-row 직렬화도 남는다.

근거: [nginx least_conn/random 공식 문서](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#random).
변경 당시 nginx 1.28 이미지에서 -t 구문 검증을 수행했다. 후속 사용자 실행의 분포와 결과는
[실험 기록](../../artifacts/performance/README.md)에 보존하며 배정 변경 하나의 인과 효과로 단정하지 않는다.
AdmissionGate, 풀·스레드·타임아웃, 워밍업 패턴, 구매 트랜잭션의 비즈니스 실행 순서는 유지한다.
전후 효과를 분리하려면 같은 관측 조건의 fa3103f와 이후 커밋을 비교한다. 과거 미계측 실행과 직접 성능 향상을 주장하지 않는다.
