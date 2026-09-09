# 초기 지연을 구분하는 방법

AdmissionGate OFF, 구매 10 RPS/30초, VU 20, API별 Hikari 8/Tomcat 40, timeout 1초를 유지한다.
Run -Diagnostics를 선택하면 구매별 단계 로그와 워밍업 DB 관측기를 켠다. Check는 읽기 전용이다.
이 옵션은 낮은 부하의 원인 진단용이다. 로그와 관측 SQL도 자원을 쓰므로 고부하 성능값과 직접 비교하지 않는다.
일반 Run에서도 nginx 요청 로그, 1초 Prometheus, 워밍업 raw와 실패 자료는 저장한다.

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
