# 다음 단계 준비와 부하테스트 가이드 — Git Bash

현재는 기능을 이해하고 검증하는 단계다. 이 문서 작성 과정에서는 부하테스트를 실행하지 않았다.
아래 **3번부터는 나중에 직접 측정을 시작할 때** 사용하는 절차다.

## 1. 지금 만들어진 것

- Nginx → API 2개 → PostgreSQL, 별도 Worker와 Mock PG. 모두 로컬 Compose로 실행한다.
- 여러 상품의 원자적 점유, 인당 제한, 멱등 구매, 결제 접수/확정, 만료/반환.
- DB에 결제 작업을 남기고 Worker가 처리한다. HTTP 202는 접수이며 CONFIRMED가 결제 완료다.
- Redis gate를 켜면 동시 구매 진입 수를 제한하고 소진 결과를 1초 캐싱한다. 실제 재고 원장은 DB다.
- PostgreSQL 하나의 dev/test/perf DB, 환경별 Redis 키, Flyway와 초기화 스크립트.
- PostgreSQL·Redis 기능 테스트 22개, 3건짜리 스모크, k6 최초 도착 부하 스크립트, 선택적 Prometheus.

코드 읽기: [learning.md](learning.md). 목표와 자원 예산: [performance.md](performance.md).
재시도 폭주·구매 포기·반환 재고 재구매를 포함한 전체 시나리오, API 1개/2개 공정 비교,
자동 성능 보고서는 아직 완성되지 않았다.

## 2. 부하 없이 현재 버전 확인하기

Git Bash에서 저장소로 이동한다. JDK 21, Docker Desktop, Git Bash, PowerShell 7(`pwsh`)이 필요하다.
기존 관리 스크립트가 PowerShell이므로 Git Bash에서 pwsh로 호출한다.

```bash
cd /d/Code/limited-goods-reservation
java -version
docker compose version
pwsh --version

docker compose up -d --wait postgres redis
./gradlew.bat --no-daemon test bootJar
ADMISSION_ENABLED=true docker compose up -d --build --wait --wait-timeout 240
pwsh -NoProfile -File ./ops/smoke.ps1
```

명령 하나가 실패하면 다음 명령으로 넘어가지 않고 그 출력을 확인한다.
test는 test DB를 초기화한다. perf 측정과 동시에 실행하지 않는다.
Mock PG → API/Worker → Nginx가 준비된 뒤 시작하므로 느린 최초 JVM 기동은 결제 대기와 분리된다.
스모크는 새 dev 판매의 3건을 정상/응답 유실/지연 결제로 확정하고, 소진 후 거절과 멱등 재조회까지 확인한다.
이는 기능 확인이며 처리량이나 지연시간 목표의 합격 증거가 아니다.

## 3. 나중에 무엇을 측정할까

먼저 **동일 자원에서 gate OFF/ON**만 비교한다. 한 번에 서버 수와 pool까지 함께 바꾸지 않는다.
`k6/purchase-spike.js`는 1,000개 판매를 생성하고 첫 10초 3,000 RPS,
다음 50초 400 RPS의 최초 구매 시도를 보낸다. 성공한 점유는 SUCCESS 결제를 접수한다.
구매 요청 RPS와 결제를 포함한 전체 HTTP RPS는 다르다.

확인할 항목은 다음과 같다.

- 목표 요청량을 실제로 보냈는가: dropped_iterations, 실제 요청 수, 생성기 자원.
- 무엇을 반환했는가: 점유 성공/409/429/예상 밖 오류의 수와 각 지연시간.
- 결제가 끝났는가: 202 접수 수, CONFIRMED 수, 잔여 held/UNKNOWN, 최종 재고 불변식.
- 어디서 기다렸는가: API/DB/Worker CPU·메모리, DB 락 대기, Hikari 대기.

현재 k6의 지연 threshold는 전체 HTTP 집계이며 결제 접수 전용 p95 검사를 포함하지 않는다.
409도 코드별로 세분하지 않는다. 기본 요약만으로 전체 목표 합격을 판정하지 않는다.
태그별 분석이 필요하면 아래 raw.json을 사용한다. 반환 재고 재구매/재시도 부하는 별도 시나리오 보완이 필요하다.

## 4. 측정용 환경 준비 — 아직 실행하지 않아도 됨

이후 명령들은 같은 Git Bash 창에서 순서대로 실행한다.
이전 부하 발생기와 테스트를 종료하고 결과를 저장한 다음 초기화한다.
perf.env의 gate 기본값은 true지만 아래 export가 우선한다.

```bash
MODE=baseline
export ADMISSION_ENABLED=false
# 비교 실행 때는 MODE=gate, ADMISSION_ENABLED=true로 바꾼다.

docker compose --profile observe stop
# 최초 perf DB 마이그레이션 적용. 기존 볼륨은 유지한다.
docker compose --env-file ops/perf.env up -d --wait --wait-timeout 240
pwsh -NoProfile -File ./ops/reset-db.ps1 -Environment perf
docker compose --env-file ops/perf.env up -d --wait --wait-timeout 240
docker compose --env-file ops/perf.env --profile observe up -d prometheus
pwsh -NoProfile -File ./ops/smoke.ps1
```

초기화는 API/Worker/Mock PG를 먼저 멈추고 perf 테이블과 goods:perf: 키만 지운다.
Flyway 이력, dev 데이터, 볼륨은 유지한다. down -v는 초기화 명령으로 사용하지 않는다.
스모크가 만든 판매는 측정 판매와 다르므로 나중에 전체 DB 판매량을 측정 판매량으로 혼동하지 않는다.
이 소량 호출만으로 JVM이 충분히 워밍업됐다고 간주하지 말고, 본 측정 전 워밍업 조건도 정한다.

## 5. 사용자가 측정을 시작할 때만 실행

다음 블록이 실제 부하를 발생시킨다. RPS를 바꾸면 목표 시나리오와 다른 실험임을 기록한다.
Git Bash가 컨테이너 경로를 Windows 경로로 변환하지 않도록 MSYS_NO_PATHCONV를 사용한다.

```bash
RUN_DIR="artifacts/performance/$(date +%Y%m%d-%H%M%S)-$MODE"
mkdir -p "$RUN_DIR"
K6_DIR="$(cygpath -m "$PWD/k6")"
OUT_DIR="$(cygpath -m "$PWD/$RUN_DIR")"
git rev-parse HEAD > "$RUN_DIR/commit.txt"
docker compose --env-file ops/perf.env config > "$RUN_DIR/compose.yaml"

MSYS_NO_PATHCONV=1 docker run --rm --name goods-k6 \
  --cpus 1.5 --memory 1g \
  --mount "type=bind,source=$K6_DIR,target=/scripts,readonly" \
  --mount "type=bind,source=$OUT_DIR,target=/results" \
  -e OPENING_RPS=3000 -e TAIL_RPS=400 \
  grafana/k6:0.54.0 run \
  --summary-export=/results/summary.json --out json=/results/raw.json \
  /scripts/purchase-spike.js 2>&1 | tee "$RUN_DIR/k6.log"
K6_EXIT=${PIPESTATUS[0]}
echo "$K6_EXIT" > "$RUN_DIR/exit-code.txt"
```

raw.json은 커질 수 있고 생성기 I/O에도 영향을 준다. 비교할 때 같은 수집 조건을 사용한다.
테스트 중 다른 Git Bash 창의 `docker stats`와 http://localhost:9090의 Prometheus를 확인한다.
현재 Prometheus는 JVM/HTTP/Hikari 등 앱 메트릭을 수집한다. PostgreSQL 락 통계는 SQL로 별도 확인해야 한다.

## 6. 측정 후 읽는 순서

Worker가 남은 작업을 처리하도록 기다린 뒤, 상태와 불변식을 저장한다.
아래 30초는 우선 관찰 시점일 뿐 모든 결제가 끝났다는 보장은 아니다.

```bash
sleep 30
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U goods \
  -d limited_goods_perf < ops/invariants.sql > "$RUN_DIR/db-check.txt"
docker compose logs --no-color > "$RUN_DIR/services.log"
docker compose ps -a > "$RUN_DIR/containers.txt"
```

먼저 종료 코드와 dropped_iterations를 확인한다. 요청을 덜 보냈다면 목표 부하를 버텼다고 할 수 없다.
불변식 쿼리의 첫 결과는 0행이어야 한다. 뒤의 주문/결제 집계에는 워밍업 판매도 포함된다.
k6 로그의 Measured sale id로 측정 판매를 구분하고, 해당 판매의 확정 수량과 남은 점유를 확인한다.
실패가 남았다면 이유를 확인한 뒤 초기화한다. raw 기록은 의도적으로 Git에서 제외돼 있다.

빠른 429/409만 많아진 것은 판매 흐름의 개선과 다르다. 같은 자원과 요청 조건에서 결과를 비교하고,
차이를 설명할 수 있을 때 다음 대안을 고른다. 전체 비즈니스 합격 판정과 대표 3회 반복은 그 이후 단계다.
