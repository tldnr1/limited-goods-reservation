# 수동 hot-row lock diagnostic baseline

## Question

단일 hot Inventory를 구매하는 부하에서 관찰된 PostgreSQL 잠금 대기가 connection 장기 점유와 SQLAlchemy pool saturation으로 전파된다는 가설을 수동 진단 자료로 뒷받침할 수 있는가?

이 문서는 구현 변경 전 비교 기준을 소유한다. 계측 정의와 실행 절차는 [성능 측정 가이드](performance.md)를 참고한다. `capacity-overload-by-chatgpt` 자료와 기존 가이드의 자동화 비교 결과는 이 baseline의 공식 증거에서 제외한다.

## Experiment conditions

대표 수동 자료는 다음 두 run이다. 각 디렉터리의 `commit.txt`, `preflight.txt`, `timing.json`, `k6-summary.json`, `k6-output.log`, PNG를 함께 보존한다.

- [70 RPS / 2026-09-05](../artifacts/performance/20260905-manual-lock-pool-diagnostic/rps-70-run-1/): 23:54:26–23:55:38 KST.
- [80 RPS / 2026-09-06](../artifacts/performance/20260906-manual-lock-pool-diagnostic/rps-80-run-1/): 00:09:21–00:10:34 KST. 추가로 `postgres-waits.csv`를 보존한다.

두 run의 기록된 코드 revision은 `48cac8f25a292c2796ff1303d8c92d8f98d0c3d3`이다. 목표 부하는 단일 상품 신규 구매이며, timing에는 본 측정 60초, pre-allocated VU 200, 재고 1,000,000, warm-up 10 RPS × 20초, 분리 15초가 기록되어 있다. k6 summary의 setup에는 run마다 하나의 sale item이 있다. preflight에는 PostgreSQL 16 Alpine, API 1개, Docker CPU 12개·메모리 7.715 GiB가 기록되어 있고 Worker는 실행 목록에 없다. 현재 구매 코드의 pool 설정은 10 + 20이다.

preflight는 실행 전 순간 관측이다. 실행 중 자원 사용, 모든 transaction의 정리 여부, 결제 호출 부재를 독립적으로 증명하지 않는다. `commit.txt` 역시 당시 working tree가 clean이었음을 증명하지 않는다.

## Stable / transition / overload observations

사용자가 보고한 탐색 관찰은 60 RPS 부근의 안정 run 존재, 70 RPS의 run-to-run variability, 80 RPS / 200 VU의 반복 overload다. 보존된 대표 수동 자료에는 60 RPS run이나 동일 RPS 반복 run이 없으므로 이 문서는 그 안정성·반복성을 artifact로 검증한 사실로 확정하지 않는다.

아래 수치는 두 수동 `k6-summary.json`에서 확인한 사실이다. HTTP 지연은 전체 HTTP 요청 기준이며, 구매 check 수와 구분한다.

| 목표 RPS | 완료 구매 iteration | 구매 created check 성공 / 실패 | dropped | HTTP avg / p95 / max | interrupted iteration (log) |
|---:|---:|---:|---:|---:|---:|
| 70 | 1,579 | 1,569 / 10 | 2,421 | 2.021 / 2.173 / 32.860초 | 200 |
| 80 | 473 | 330 / 143 | 4,251 | 21.190 / 59.999 / 59.999초 | 77 |

각 summary의 `http_reqs`는 setup 요청 1건을 포함해 각각 1,580건, 474건이다. iteration rate 22.551/s, 6.756/s는 종료 대기를 포함한 k6 요약 값이며 60초 동안의 성공 구매 처리량으로 해석하지 않는다. 두 run 모두 목표 부하를 유지하지 못했다. 80 RPS log에는 `request timeout`이 기록되어 있다. exit code 0은 성능 합격을 뜻하지 않는다.

70 RPS PNG에는 `checked_out`이 capacity 30에 도달하고 HTTP 지연이 상승한 구간이 보인다. 80 RPS PNG의 pool 패널은 비어 있어 이 run의 pool peak를 숫자로 확정할 수 없다. 두 PNG 모두 시계열이 일부 구간에만 남아 있다. 그래프 공백을 0 또는 회복으로 간주하지 않으며, 공백의 원인을 이미지 자체로 확정하지 않는다.

## PostgreSQL wait diagnostic result

[80 RPS CSV](../artifacts/performance/20260906-manual-lock-pool-diagnostic/rps-80-run-1/postgres-waits.csv)는 2026-09-05 15:09:19.034330–15:10:30.534520 UTC, 즉 2026-09-06 00:09:19–00:10:30 KST의 700개 표본이다. 측정 시작 부근 0이던 lock waiter가 증가하며, `lock_waiters`, `blocked_sessions`, `ungranted_locks`의 표본 최댓값은 각각 28이다. `Lock:transactionid`, `Lock:tuple` 대기가 관찰된다.

대기 SQL 앞부분은 `SELECT sale_items.id, ... inventories.sale_item_id, ...`다. 기록된 revision의 purchase service에서 이 SELECT는 `ORDER BY SaleItem.id FOR UPDATE OF Inventory` 조회에 대응한다. 따라서 purchase path의 Inventory 잠금 경합과 연결된다. 단, [sampler](../ops/performance/sample-postgres-waits.sql)는 SQL을 240자로 잘라 저장하므로 CSV에 `FOR UPDATE` 절이나 개별 hot-row 식별자까지 직접 기록된 것은 아니다. 쿼리 식별에는 현재 코드와 단일 상품 workload를 함께 사용했다.

## Current diagnosis

single hot Inventory row의 FOR UPDATE contention이 PostgreSQL에서 실제로 관찰되었고, 이것이 connection 장기 점유와 pool saturation으로 전파되는 가설을 지지한다.

잠금 대기는 80 RPS CSV, pool capacity 도달은 70 RPS PNG, HTTP 지연·dropped는 두 k6 summary로 확인된다. 80 RPS에서 pool saturation까지 이어지는 전체 시간적 인과관계를 이 자료만으로 확정하지 않는다. pool size 자체가 root cause라는 결론도 내리지 않는다.

## Hypothesis

현재 구매는 Inventory lock을 획득한 뒤 usage 조회, 검증, Order·OrderItem·Reservation 생성과 INSERT, Inventory UPDATE, commit을 수행한다. 같은 transaction에서 Order·OrderItem을 먼저 flush하고 Inventory lock을 늦게 획득하면 lock 이후 DB 작업량이 줄어들 수 있다. lock 이후 새 SQL로 usage를 조회하고 자기 tentative Order만 제외해야 READ COMMITTED에서 기존 동시성 계약을 보존할 수 있다.

이는 검증할 가설이다. 전체 transaction 시간이나 connection 점유 시간이 반드시 줄어든다는 뜻은 아니다. Reservation TTL 계산 의미는 이번 구현에서 유지한다.

## Limitations

- 대표 수동 artifact는 RPS별 1회다. 정확한 maximum capacity 또는 안정 하한을 확정하지 않는다.
- 80 RPS에만 wait CSV가 있고, Grafana raw 시계열과 API 오류 로그, 실행 후 재고 정합성 결과는 이 대표 자료에 포함되어 있지 않다.
- sampler는 표본 관측이며 Inventory lock hold time을 직접 측정하지 않는다. `inventory_lock` stage도 쿼리 대기와 실행을 포함하며 보유 시간이 아니다.
- 같은 host에서의 관측 비용·실행 환경 변동과 앞선 부하의 잔류 상태를 완전히 분리하지 못한다.
- client timeout이나 interrupted iteration은 DB rollback을 증명하지 않는다. 성능 결과만으로 oversell·부분 상태 부재를 주장하지 않는다.
- READ COMMITTED는 소유자가 별도로 확인한 환경 조건이며 이 artifact의 preflight에 기록된 검증 결과는 아니다.

## Next experiment

구매 정합성 테스트를 통과한 별도 implementation commit 이후, 소유자가 같은 80 RPS / 200 VU workload로 before/after를 실행한다. API·DB 환경, 초기 데이터, warm-up·분리·측정 시간, Worker·결제 조건을 맞추고 각 revision과 실행 조건을 기록한다. 이번 baseline 정리에서는 부하나 DB 테스트를 실행하지 않았다.

비교 항목은 created 성공 수·실제 처리율, k6 dropped/interrupted·HTTP 실패/timeout·p95/p99, purchase 전체 및 `inventory_lock`/`usage_lookup`/`commit`/`connection_checkout` stage 지연, pool checked-out/capacity와 포화 지속, PostgreSQL lock waiter/blocked session/대기 query다. 계측 공백과 사후 재고·주문 정합성도 함께 확인한다. commit stage 감소만으로 성능 개선을 확정하지 않는다.
