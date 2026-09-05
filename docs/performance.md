# 성능 측정 가이드

이 문서는 현재 관측 구성을 찾는 출발점이자, 첫 capacity 실험의 계획서다. 아직 측정하지 않은 숫자를 목표처럼 적지 않고, 실험 결과가 생기면 같은 문서의 결과 표와 해석을 갱신한다.

## 먼저 구분할 것

- **Capacity 측정**은 재고 부족 같은 의도된 실패를 제거한 뒤, 현재 구성에서 처리량과 지연이 어디서 무너지기 시작하는지 찾는다.
- **Workload 검증**은 그렇게 파악한 범위 안에서 오픈 직후 burst, 단일 재고 쏠림, 재시도처럼 실제 서비스에 가까운 요청 형태를 견디는지 확인한다.

첫 실험은 `POST /purchases`의 단일 상품 신규 구매 성공 경로만 다룬다. 충분한 재고와 매 요청마다 다른 사용자·멱등키를 사용한다. 결제 API는 호출하지 않고 Worker는 중지한다. 따라서 이 결과는 결제까지 포함한 전체 서비스 capacity가 아니다.

## 값이 그래프가 되는 과정

```text
애플리케이션 코드
  └─ Counter / Histogram / Gauge를 프로세스 메모리에 기록
       └─ GET /metrics가 현재 누적값을 노출
            └─ Prometheus가 1초마다 값을 가져와 시계열로 저장
                 └─ Grafana가 PromQL로 증가율·분위수를 계산해 표시

k6
  └─ 목표 RPS로 HTTP 요청을 발생시키고 클라이언트 관점 결과를 별도로 요약
```

Counter와 Histogram은 `/metrics`에서 계속 증가하는 누적값이다. 그래서 Grafana는 Counter에 `rate(...[10s])`, Histogram bucket에 `rate`와 `histogram_quantile`을 적용한다. Gauge는 연결 풀의 현재 사용량처럼 그 시점의 값을 그대로 읽는다. 1초 scrape와 1초 dashboard refresh는 짧은 변화를 보기 위한 로컬 실험 설정이고, 그래프의 10초 구간은 지나친 흔들림을 줄이기 위한 첫 기본값이다.

현재 연결 위치는 다음과 같다.

- 계측 정의: [`src/limited_goods/metrics.py`](../src/limited_goods/metrics.py)
- HTTP 계측: [`src/limited_goods/main.py`](../src/limited_goods/main.py)
- 구매 구간 계측: [`src/limited_goods/purchases/service.py`](../src/limited_goods/purchases/service.py)
- SQLAlchemy 풀 계측: [`src/limited_goods/db.py`](../src/limited_goods/db.py)
- Prometheus 수집 설정: [`ops/prometheus.yml`](../ops/prometheus.yml)
- Grafana datasource와 dashboard 연결: [`ops/grafana/provisioning`](../ops/grafana/provisioning)
- dashboard 원본: [`ops/grafana/dashboards/purchase-capacity.json`](../ops/grafana/dashboards/purchase-capacity.json)
- 실행 컨테이너 연결: [`compose.yaml`](../compose.yaml)
- k6 예제: [`k6/capacity/purchase.js`](../k6/capacity/purchase.js)

## 첫 실험에 사용하는 관측값

| 계층 | metric / 유형 | 측정 위치 | 알 수 있는 것 | 이것만으로 모르는 것 | 이상 시 다음 관측 |
|---|---|---|---|---|---|
| 애플리케이션 | `limited_goods_http_requests_total` / Counter | HTTP middleware | 서버가 실제로 완료한 요청 수와 상태별 처리율 | k6가 시작하지 못한 요청, 내부 병목 위치 | k6 `dropped_iterations`, 구매 구간 지연 |
| 애플리케이션 | `limited_goods_http_request_duration_seconds` / Histogram | HTTP middleware | API 전체 p50·p95·p99 지연 | DB, 직렬화, 네트워크 중 원인 | service total과 구간별 지연 비교 |
| 애플리케이션 | `limited_goods_purchase_outcomes_total` / Counter | 구매 서비스 진입부터 종료까지 | `created`, `reused`, `rejected`, `conflict`, `error` 비율 | 거절의 세부 사유와 DB 정합성 | 응답 error code, 로그, 사후 재고 조회 |
| 애플리케이션 | `limited_goods_purchase_duration_seconds` / Histogram | 구매 서비스 전체 | FastAPI 바깥 비용을 제외한 구매 로직 지연 | 느린 내부 구간 | 구간별 Histogram |
| 애플리케이션 | `limited_goods_purchase_stage_duration_seconds` / Histogram | 멱등 조회, 판매 조회, 재고 잠금 쿼리, 사용량 조회, commit 주위 | 어느 DB 구간의 시간이 함께 증가하는지 | `inventory_lock` 중 순수 lock wait와 쿼리 실행 시간의 분리 | PostgreSQL activity·lock 관측 |
| SQLAlchemy | `limited_goods_db_pool_connections` / Gauge | engine pool의 `checked_in`, `checked_out` 현재값 | 연결 사용량과 고갈 징후 | checkout 대기시간, 아주 짧은 포화 | pool timeout/checkout 계측과 PostgreSQL connection 확인 |
| SQLAlchemy | `limited_goods_db_pool_capacity` / Gauge | pool 설정 `10 + 20` | 현재 API 프로세스가 열 수 있는 최대 연결 수 | 그 크기가 적절한지 | DB 동시 연결과 CPU를 함께 비교 |
| 애플리케이션 런타임 | `process_cpu_seconds_total`, `process_resident_memory_bytes` / Counter·Gauge | Prometheus Python client 기본 collector | API 프로세스 CPU 사용률과 RSS 변화 | 컨테이너 제한, PostgreSQL·host 자원 | `docker stats`, host/container exporter |
| k6 | `http_reqs`, `http_req_duration`, `checks`, `dropped_iterations` / Counter·Trend·Rate·Counter | 부하 발생기 | 보낸 요청 수, 클라이언트 지연, 201 비율, 목표 도착률 누락 | 서버 내부 원인 | 같은 시간대 Grafana 지표 |

`inventory_lock`은 `SELECT ... FOR UPDATE` 호출 전체를 잰다. 이름과 달리 순수 잠금 대기시간만 재는 값은 아니다. 이 값이 커졌을 때 비로소 `pg_stat_activity`, `pg_locks` 같은 PostgreSQL 자체 관측으로 내려간다.

### 지금은 수집하지 않는 것

- PostgreSQL의 activity, lock, query 통계는 Prometheus로 수집하지 않는다. 첫 그래프에서 의심 구간을 찾은 후 수동 SQL 또는 exporter 도입 여부를 결정한다.
- Docker 전체와 host CPU·메모리는 dashboard에 넣지 않았다. 필요하면 우선 `docker stats`로 API, DB, Prometheus, Grafana를 구분해 본다.
- Worker는 별도 프로세스라 현재 Worker 내부 Counter가 API의 `/metrics`에 나타나지 않는다. 첫 구매 capacity에서는 Worker 자체를 중지하므로 이 문제를 함께 해결하지 않는다.
- Payment metric은 결제 API를 호출하지 않으므로 첫 실험의 판단 근거에서 제외한다.

## Grafana에서 보는 여섯 패널

Compose를 시작하면 `http://localhost:3000`의 `Limited Goods / Purchase Capacity` dashboard가 파일에서 자동으로 만들어진다.

| 패널 | 눈으로 찾을 것 |
|---|---|
| 처리량: 요청과 구매 결과 | 목표 RPS를 올려도 HTTP와 `created` 처리율이 같이 올라가는가. 둘 사이 간격이나 다른 outcome이 생기는가 |
| HTTP 응답 시간 | RPS의 어느 단계부터 p95·p99가 p50과 크게 벌어지는가 |
| 구매 내부 구간 응답 시간 | service total 상승과 함께 어느 DB 구간 p95가 먼저 올라가는가 |
| SQLAlchemy 연결 풀 | `checked_out`이 capacity 30에 계속 붙어 있는가 |
| API 프로세스 CPU | 처리량 정체 시 CPU core 사용량도 함께 포화되는가 |
| API 프로세스 메모리 | 단계가 올라갈수록 회수되지 않는 상승이 있는가 |

그래프 하나만으로 원인을 확정하지 않는다. 예를 들어 `checked_out=30`과 `idempotency_lookup` 상승이 같은 시각에 나타나면 pool 대기를 의심할 수 있지만, PostgreSQL 연결·activity를 확인하기 전에는 결론이 아니다.

## 첫 capacity 실험 예제

### 1. 고정할 조건

- API 인스턴스 1개, SQLAlchemy pool `10 + 20`, PostgreSQL 1개
- 단일 상품, 수량 1, 충분한 재고, 요청마다 다른 사용자와 멱등키
- Worker 중지, 결제 호출 없음
- 한 번의 run은 constant arrival rate 60초
- run마다 k6 `setup()`이 새 SaleEvent와 SaleItem을 만든다

Worker가 실행 중이면 60초 TTL이 지난 Reservation을 만지기 시작해 같은 DB에 별도 부하를 만든다. 따라서 첫 실험에서는 다음처럼 중지하고, 일반 기능 확인으로 돌아갈 때 다시 시작한다.

```powershell
docker compose up -d --build
docker compose stop worker
```

### 2. 가장 작은 확인 실행

로컬에 설치된 k6를 사용하는 PowerShell 예시다.

```powershell
$env:BASE_URL = "http://localhost:8000"
$env:RATE = "10"
$env:DURATION = "60s"
$env:STOCK = "1000000"
$env:PRE_ALLOCATED_VUS = "20"
k6 run k6/capacity/purchase.js
```

macOS에서는 Docker Desktop과 k6가 준비되어 있으면 Compose 명령은 동일하고, 환경변수만 zsh/bash 문법으로 지정한다. k6는 Homebrew의 `brew install k6`로 설치할 수 있다.

```bash
export BASE_URL="http://localhost:8000"
export RATE="10"
export DURATION="60s"
export STOCK="1000000"
export PRE_ALLOCATED_VUS="20"
k6 run k6/capacity/purchase.js
```

Apple Silicon에서도 Compose가 각 이미지의 ARM64 variant를 자동으로 선택한다. k6를 Docker 컨테이너로 실행하는 경우에만 `localhost`가 API 컨테이너가 아닌 k6 컨테이너 자신을 가리키므로 `BASE_URL=http://host.docker.internal:8000`처럼 바꿔야 한다. 첫 학습 실행은 위의 macOS native k6 방식을 기준으로 한다.

k6는 open model인 `constant-arrival-rate`로 응답이 느려져도 초당 시작할 iteration 수를 유지하려 한다. `dropped_iterations`가 0이 아니면 서버 capacity라고 결론 내리기 전에 `PRE_ALLOCATED_VUS`와 부하 발생기 CPU가 충분한지 먼저 확인한다.

### 3. RPS를 올리는 방식

처음에는 `10 → 20 → 40 → 80 ...`처럼 두 배씩 올려 마지막 정상 구간과 첫 이상 구간을 찾는다. 그 사이만 더 작은 간격으로 다시 측정한다. 숫자 자체는 장비 성능을 모르므로 예시이며, 아직 합격 p95나 목표 RPS를 정하지 않는다.

다음 중 하나가 처음 나타나는 구간을 기록한다.

- k6의 201 check 비율이 내려가거나 예상하지 않은 outcome이 생김
- VU를 충분히 줬는데도 처리량이 목표 RPS를 따라가지 못함
- p95·p99가 이전 단계보다 비선형적으로 증가함
- DB pool 또는 API CPU가 지속적으로 포화됨

각 run마다 아래를 한 줄로 남긴다.

| 조건 | k6 실제 req/s | dropped | 201 비율 | HTTP p50/p95/p99 | 가장 느린 stage p95 | pool peak | CPU peak | 해석 |
|---|---:|---:|---:|---|---|---:|---:|---|
| 실행 전 | - | - | - | - | - | - | - | - |

테스트가 끝나면 k6 setup 로그의 `sale_event_id`로 `GET /sales/{sale_id}`를 조회한다. Worker를 멈춘 성공 경로에서는 `available + held + sold = total`이고, 생성된 구매 수와 `held` 증가량이 같아야 한다. 성능 수치보다 이 정합성 확인이 먼저다.

현재 예제는 run마다 새 판매를 만들지만 이전 주문 데이터까지 지우지는 않는다. 학습용 첫 실행에는 충분하지만 공식 비교 결과를 남기기 전에는 동일한 DB snapshot에서 시작하는 초기화 방법을 별도로 합의해야 한다.

## 첫 smoke 실행 기록

2026-09-05에 Windows 로컬 환경에서 `10 RPS × 60초` smoke를 실행했다. 이 실행의 목적은 한계를 찾는 것이 아니라, k6 요청부터 Prometheus 수집과 Grafana 표시, 사후 재고 검증까지 측정 경로가 이어지는지 확인하는 것이었다.

### 실행 결과

| 조건 | 구매 iteration/s | dropped | 201 비율 | HTTP avg / p95 / max | pool peak | API CPU peak | API RSS peak | 해석 |
|---|---:|---:|---:|---|---:|---:|---:|---|
| 10 RPS, 60초, Worker 중지 | 10.003 | 0 | 100% (601/601) | 17.34 / 20.23 / 71.11 ms | 1 | 0.122 core | 88.24 MiB | smoke 통과. 이 부하만으로 capacity 여유 폭이나 병목은 판단하지 않음 |

k6의 전체 HTTP 요청 602건에는 `setup()`의 판매 생성 1건이 포함된다. 구매 자체는 601건이며, 종료 후 재고는 `available 999,399 + held 601 + sold 0 = total 1,000,000`이었다. Worker를 실행하지 않았으므로 구매 수와 held 증가량도 일치한다.

Grafana의 10초 `rate()`와 Histogram 분위수는 시간에 따른 모양을 보는 값이다. 이 run처럼 새 label 시계열이 첫 요청과 함께 생기는 짧은 테스트에서는 Prometheus의 첫 scrape 전에 처리된 수가 구간 증가량에서 빠질 수 있다. 따라서 총 요청 수와 합격 여부는 k6 요약과 사후 데이터 검증을 기준으로 하고, Grafana는 처리량 유지·지연 변화·내부 구간의 동시 변화를 읽는 데 사용한다.

- [실행 메타데이터와 환경](../artifacts/performance/20260905-084524-smoke-r10/run-metadata.json)
- [k6 요약](../artifacts/performance/20260905-084524-smoke-r10/k6-summary.json)
- [k6 전체 출력](../artifacts/performance/20260905-084524-smoke-r10/k6-output.log)
- [고정 시간대 Grafana 화면](../artifacts/performance/20260905-084524-smoke-r10/grafana-dashboard.png)

### Windows에서 같은 smoke를 직접 실행하는 순서

현재 PC에는 native k6가 없어 공식 Docker image를 사용했다. 프로젝트 루트의 PowerShell에서 아래 순서로 실행한다.

1. 구매 흐름에 필요한 컨테이너만 시작한다. Worker는 처음부터 제외한다.

   ```powershell
   docker compose up -d --build db migrate mock-pg api prometheus grafana
   ```

2. API와 관측 도구가 준비됐는지 확인한다.

   ```powershell
   Invoke-WebRequest -UseBasicParsing http://localhost:8000/health
   Invoke-WebRequest -UseBasicParsing http://localhost:9090/-/ready
   Invoke-RestMethod http://localhost:3000/api/health
   ```

3. Chrome에서 Grafana를 열고 테스트 전후 그래프를 본다.

   ```powershell
   Start-Process "C:\Program Files\Google\Chrome\Application\chrome.exe" `
     "http://localhost:3000/d/purchase-capacity/purchase-capacity?orgId=1&from=now-5m&to=now"
   ```

4. 결과 폴더를 만든 뒤 k6를 Docker로 실행한다. k6 컨테이너에서 Windows host의 API를 부르므로 `host.docker.internal`을 사용한다.

   ```powershell
   $runId = Get-Date -Format "yyyyMMdd-HHmmss"
   $resultDir = Join-Path $PWD "artifacts/performance/$runId-smoke-r10"
   New-Item -ItemType Directory -Path $resultDir | Out-Null
   $testStart = Get-Date

   docker run --rm `
     -e BASE_URL=http://host.docker.internal:8000 `
     -e RATE=10 `
     -e DURATION=60s `
     -e STOCK=1000000 `
     -e PRE_ALLOCATED_VUS=20 `
     -v "${PWD}/k6:/scripts:ro" `
     -v "${resultDir}:/results" `
     grafana/k6:latest run `
     --summary-export=/results/k6-summary.json `
     /scripts/capacity/purchase.js 2>&1 |
     Tee-Object -FilePath (Join-Path $resultDir "k6-output.log")

   $testEnd = Get-Date
   ```

5. 출력에 찍힌 `sale_event_id`로 재고 합계를 확인한다.

   ```powershell
   $saleId = "출력된-sale_event_id"
   Invoke-RestMethod "http://localhost:8000/sales/$saleId" |
     ConvertTo-Json -Depth 5
   ```

6. 테스트 시작 10초 전과 종료 10초 후를 Unix millisecond로 바꿔 Grafana URL의 `from`, `to`에 넣는다. `now` 대신 절대시간을 쓰면 화면을 다시 열어도 관찰 구간이 움직이지 않는다.

   ```powershell
   $fromMs = ([DateTimeOffset]$testStart).AddSeconds(-10).ToUnixTimeMilliseconds()
   $toMs = ([DateTimeOffset]$testEnd).AddSeconds(10).ToUnixTimeMilliseconds()
   $fixedUrl = "http://localhost:3000/d/purchase-capacity/purchase-capacity?orgId=1&from=$fromMs&to=$toMs"
   Start-Process "C:\Program Files\Google\Chrome\Application\chrome.exe" $fixedUrl
   ```

7. 눈으로 확인한 화면은 `Win + Shift + S`로 잘라 같은 결과 폴더에 `grafana-dashboard.png`로 저장하면 된다. 매번 같은 크기의 화면이 필요하면 이번 실행에서 사용한 Chrome headless 캡처 방식도 쓸 수 있다.

   ```powershell
   $chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
   $screenshot = Join-Path $resultDir "grafana-dashboard.png"
   $captureProfile = Join-Path $env:TEMP "grafana-capture-$runId"

   & $chrome `
     --headless=new `
     --disable-gpu `
     --hide-scrollbars `
     --window-size=1920,2200 `
     --virtual-time-budget=15000 `
     --user-data-dir=$captureProfile `
     --screenshot=$screenshot `
     $fixedUrl
   ```

이번에는 `k6-summary.json`, 실행 로그, 메타데이터, 대표 Grafana PNG를 모두 작업 폴더에 남겼지만 아직 보관 정책으로 확정하지 않는다. 포트폴리오 기준으로는 요약 수치·실험 조건·해석과 대표 이미지 1장만 Git에 남기고, 반복 run의 전체 로그는 로컬 또는 별도 저장소에 두는 편을 우선 제안한다. 원본 전체가 필요한 실패 분석 run만 예외로 보관하면 기록은 재현 가능하면서도 저장소가 실험 부산물로 커지는 것을 막을 수 있다.

## 실행 환경을 언제 분리할까

첫 학습에서는 한 host에 API·DB·Prometheus·Grafana를 두어도 된다. 이 결과는 "현재 로컬 구성 전체"의 capacity이며 각 컨테이너가 host 자원을 나누어 쓴다는 한계도 함께 기록한다.

비교 가능한 baseline을 만들 때는 최소한 k6를 다른 장비로 옮긴다. `BASE_URL`만 대상 장비 주소로 바꾸면 같은 스크립트를 쓸 수 있다. 더 엄격한 측정에서는 Prometheus와 Grafana도 관측 장비로 옮기되, 애플리케이션의 계측 비용과 `/metrics` scrape 비용은 대상에 남는다. 관측 오염을 완전히 없애는 것이 아니라, 부하 생성·시계열 저장·dashboard 조회가 대상 CPU를 뺏지 않도록 경계를 나누는 것이다.

## Capacity 다음의 workload 계획

Capacity 결과로 정상 범위와 병목 후보를 설명할 수 있게 된 뒤, 스크립트를 한꺼번에 늘리지 않고 다음 순서로 한 변수씩 바꾼다.

1. **오픈 직후 burst**: 정상 범위 안의 총 요청을 첫 5초에 집중시켜 1초 scrape 그래프에서 회복 시간을 본다.
2. **hot item과 분산 item 비교**: 같은 RPS를 한 Inventory 행과 여러 Inventory 행에 각각 보내 `inventory_lock` 차이를 본다.
3. **멱등 재시도 혼합**: 같은 요청 재전송을 섞어 신규 구매 capacity와 멱등 조회 비용을 분리한다.
4. **품절 이후 실패 집중**: 성공 처리량이 아니라 빠르고 일관된 거절과 재고 불변식을 검증한다.
5. **다중 상품 구매**: 잠그는 행 수와 원자적 실패가 지연에 미치는 영향을 확인한다.

Worker 만료와 결제 시도는 별도의 흐름이다. 구매 capacity와 섞기 전에 각각의 고정 workload와 관측 방식을 설계한다. 그래야 수치가 나빠졌을 때 어느 흐름 때문인지 설명할 수 있다.

## 참고한 도구 동작

- [k6 constant-arrival-rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/)
- [k6 arrival-rate VU allocation](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/)
- [macOS k6 설치](https://grafana.com/docs/k6/latest/set-up/install-k6/)
- [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/)
- [Prometheus scrape configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)
