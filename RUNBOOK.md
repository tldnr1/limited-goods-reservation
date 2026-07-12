# Local Runbook

이 문서는 프로젝트 실행과 실험 재현 방법을 모읍니다. 프로젝트 목적과 결과를 먼저 보려면 [README.md](README.md), 실험의 정확한 조건과 해석은 [records/experiments](records/experiments)를 확인하세요.

## 준비 사항

공식 검증 경로는 Docker Compose입니다.

- Docker Desktop 또는 Docker Engine + Compose plugin
- Windows PowerShell
- 로컬 Gradle 테스트를 직접 실행할 경우 Java 17

사용 포트:

| 서비스 | 포트 |
| --- | ---: |
| API | 8080 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Prometheus | 9090 |
| Grafana | 3000 |

## 가장 빠른 확인

### 테스트

Windows:

```powershell
.\gradlew.bat test
```

macOS/Linux:

```bash
./gradlew test
```

### Docker Compose 실행

```powershell
docker compose up --build -d
Invoke-RestMethod -Uri http://localhost:8080/actuator/health
```

정상 응답의 `status`는 `UP`입니다.

```powershell
docker compose down
```

`docker compose down`은 컨테이너와 네트워크만 내립니다. PostgreSQL, Prometheus, Grafana named volume까지 삭제하려면 별도 `-v`가 필요하므로 실험 데이터를 지울 의도가 있을 때만 사용하세요.

## 런타임 기본값 주의

아무 설정 없이 API를 실행하면 다음 값이 적용됩니다.

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `PURCHASE_ARCHITECTURE` | 빈 값 → `legacy-stock-strategy` | 기존 `StockDeductionStrategy` 기반 구매 흐름 |
| `STOCK_STRATEGY` | `naive-rdb` | 기존 흐름에서 선택할 재고 전략 |
| `WAITING_ROOM_ENABLED` | `true` | 구매 전 활성 토큰 요구 |
| `WAITING_ROOM_ADMISSION_BATCH_SIZE` | `20` | 1회 스케줄 입장 수 |
| `WAITING_ROOM_ADMISSION_ACTIVE_CAPACITY` | `100` | 동시 활성 토큰 상한 |
| `WAITING_ROOM_ACTIVE_TOKEN_TTL_SECONDS` | `60` | 활성 토큰 TTL |
| `WAITING_ROOM_ADMISSION_INTERVAL_MS` | `1000` | 입장 스케줄 간격 |
| `HIKARI_MAX_POOL_SIZE` | `10` | Docker Compose API DB pool 상한 |

v3.2 구조를 검증할 때는 수동 환경 변수 조합보다 아래 제공 스크립트를 권장합니다. 각 스크립트가 아키텍처, 대기열, 재고 초기값, 장애 주입 설정을 시나리오에 맞게 함께 맞춥니다.

## v3.2 검증

### 예약 일관성 스모크

대기열과 활성 토큰, 멱등 재시도, 중복 예약 거절, Redis 보상을 작은 데이터로 확인합니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-2\run-reservation-consistency-smoke.ps1
```

이미 API 이미지를 빌드했다면:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-2\run-reservation-consistency-smoke.ps1 -SkipBuild
```

### Redis front gate 대 RDB atomic 부하 비교

기본값은 두 구조, 네 시나리오, 1,000/3,000/5,000 VU 전체를 실행합니다. 로컬 재실행이 공식 CSV를 덮어쓰지 않도록 별도 결과 이름을 사용하세요.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-2\run-architecture-vu-matrix.ps1 -ResultName local-v3-2-architecture
```

빠른 소규모 확인 예시:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-2\run-architecture-vu-matrix.ps1 -Users "100" -Scenarios "normal,sold-out" -ResultName local-v3-2-smoke
```

결과 CSV는 `records/experiments/<ResultName>.csv`, 원본 k6 summary는 `notes/v3-2-architecture-vu/raw/`에 생성됩니다. 커밋된 공식 결과는 [v3.2 architecture load comparison](records/experiments/v3-2-architecture-load-comparison.md)에서 확인할 수 있습니다.

### RDB connection pool sweep

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\v3-2\run-architecture-pool-sweep.ps1 -ResultName local-v3-2-pool-sweep
```

기본 비교값은 Hikari pool 5/10/20/40, RDB atomic, 3,000 VU의 normal/sold-out 시나리오입니다.

## 이전 버전 재현

### v2 재고 전략 스모크

v2 runner는 재고 전략을 선택하지만, 이후 버전에서 추가된 구매 구조와 활성 토큰 설정까지 초기화하지는 않습니다. 직접 구매였던 v2 조건을 재현하려면 먼저 기존 구매 흐름을 선택하고 대기열을 끕니다.

```powershell
$env:PURCHASE_ARCHITECTURE=''
$env:WAITING_ROOM_ENABLED='false'
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy naive-rdb -Smoke
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy rdb-atomic -Smoke
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy rdb-pessimistic -Smoke
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy redis-lua -Smoke
```

전체 공식 형태는 전략마다 100/500/1,000 users를 5회 실행합니다.

```powershell
.\scripts\v2\run-stock-strategy-matrix.ps1 -Strategy redis-lua
```

기록: [v2 stock strategy comparison](records/experiments/v2-stock-strategy-comparison.md)

### v3.1 대기열 스모크

```powershell
$env:PURCHASE_ARCHITECTURE=''
powershell -ExecutionPolicy Bypass -File .\scripts\v3-1\run-entry-control-matrix.ps1 -Smoke -SkipBuild
```

기록: [v3.1 entry control](records/experiments/v3-1-entry-control.md)

## 관측 도구

Compose 실행 후 다음 주소를 사용합니다.

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3000>
- Grafana 계정: `admin` / `admin`
- Dashboard: `Limited Goods / v2 Stock Strategy Overview`
- Actuator Prometheus endpoint: <http://localhost:8080/actuator/prometheus>

대시보드와 메트릭 구성의 자세한 설명은 [monitoring/README.md](monitoring/README.md)를 참고하세요.

## DB 정합성 직접 확인

주문 기반 v1/v2 실험은 다음 쿼리로 오버셀과 주문-재고 간극을 확인할 수 있습니다.

```sql
SELECT p.id AS product_id,
       ps.initial_quantity,
       ps.sold_quantity,
       COUNT(o.id) AS successful_order_count,
       GREATEST(COUNT(o.id) - ps.initial_quantity, 0) AS oversell_count,
       GREATEST(COUNT(o.id) - ps.sold_quantity, 0) AS order_stock_gap
FROM products p
JOIN product_stock ps ON ps.product_id = p.id
LEFT JOIN orders o ON o.product_id = p.id
WHERE p.id = 1
GROUP BY p.id, ps.initial_quantity, ps.sold_quantity;
```

v3.2는 주문이 아니라 `reservations`를 비즈니스 결과로 사용하므로, 공식 스크립트가 `RESERVED` 수와 선택한 구조의 재고 의사결정 수를 비교해 `decision_reservation_gap`을 계산합니다.

## 문제 해결

- Redis 기반 경로에서 stock key missing 오류가 나면 `stock:available:{productId}`가 초기화되지 않은 상태입니다. 공식 runner는 실행 전에 이 키를 초기화합니다.
- 직접 구매 호출이 409로 거절되면 대기열이 켜진 상태에서 활성 토큰 없이 접근했는지 확인하세요.
- 로컬 8080/5432/6379/9090/3000 포트를 다른 프로세스가 사용 중이면 Compose 서비스가 시작되지 않습니다.
- 반복 실험 전에는 API health check와 시나리오별 초기화를 생략하지 마세요. 공식 runner가 이 과정을 포함합니다.
