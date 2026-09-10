# Limited Goods — Java 기준 구현

한정 굿즈의 다중 상품 점유, 멱등 구매, 비동기 결제, 만료·재고 반환을 Java 21 / Spring Boot로 구현했다.
PostgreSQL이 재고의 기준 상태를 보관하며, 선택적으로 Redis Lua 진입 제한과 짧은 재고 소진 캐시를 사용한다.

- main: 새 Java 구현
- archive/python-fastapi-baseline: Python 구현과 당시 미커밋 실험/notes/uv.lock 보존
- archive/java-spring-v3.2: 기존 Java archive 유지

이전 성능 결과는 archive에 있다. 새 Java 구현의 성능으로 인용하지 않는다.

## Target v1 첫 구현

대기/점유/결제/Worker 역할 분리, 300초 점유, 짧은 READY 입장권, Worker 연속 작업 공급을 추가했다.
대규모 성능 목표는 아직 미검증이다. 계약·제약과 실행법은 [Target v1](docs/target-v1.md)을 따른다.

```powershell
.\ops\target.ps1 -Action Start
.\ops\target.ps1 -Action Smoke -RedisOutage
.\ops\target.ps1 -Action Stop
```

대기는 localhost:8080, 구매/결제는 localhost:8082다. 아래 기본 Compose는 기존 baseline 역할 구성이며,
Target과 동시에 실행하지 않는다. 두 구성 모두 새 점유는 300초, Mock PG 최초 처리 창은 접수+70초다.

## 실행과 확인 (PowerShell)

JDK 21 및 Docker Desktop이 필요하다. Gradle은 Wrapper가 내려받는다.

```powershell
docker compose up -d postgres redis
.\gradlew.bat --no-daemon test bootJar
docker compose up -d --build --wait --wait-timeout 240
.\ops\smoke.ps1
```

접속 주소는 http://localhost:8080이다. Compose는 Mock PG의 Spring readiness를 확인한 뒤 API·Worker를 시작한다.
Nginx는 API 두 개와 Worker가 healthy일 때 시작한다. smoke 스크립트도 이 네 프로세스의 health를 먼저 검사한다.
기동 대기와 결제 확인 대기는 별개다. baseline smoke의 결제 확인 제한은 30초다.
통합 테스트는 실제 PostgreSQL의 limited_goods_test와 Redis의 테스트 namespace만 사용한다.
test 실행 시 해당 테스트 DB는 각 테스트 전에 초기화된다. perf 실행과 동시에 테스트하지 않는다.

Redis gate는 기본값 false로 DB 기준선부터 확인한다. 활성화 예:

```powershell
$env:ADMISSION_ENABLED='true'
docker compose up -d --wait --wait-timeout 240
docker compose restart nginx
Remove-Item Env:ADMISSION_ENABLED
```

Compose 프로젝트/볼륨은 limited-goods-java로 기존 Python 리소스와 분리했다.
일반 종료는 docker compose stop이다. 기존 실험 데이터를 보존하려면 down -v를 사용하지 않는다.

## API

| 요청 | 내용 |
|---|---|
| POST /api/sales | 로컬 판매 등록 |
| GET /api/sales/{id} | 시작 전에도 판매·재고 조회 |
| POST /api/purchases | X-User-Id + Idempotency-Key, 다중 상품 원자적 점유, 201 |
| GET /api/orders/{id} | X-User-Id 소유자 확인, 점유·결제 상태 |
| POST /api/orders/{id}/payments | 같은 헤더, scenario 지정, 영속 접수 후 202 |
| POST /api/payments/callback | X-PG-Secret, attemptId/amount/result, 중복 안전 |

실제 요청 JSON은 [smoke.ps1](ops/smoke.ps1)을 보면 된다.
Mock PG 시나리오는 SUCCESS / FAILURE / DELAYED_SUCCESS / LOST_RESPONSE / UNKNOWN이다.
202는 결제 완료가 아니다. 주문 조회의 CONFIRMED로 완료를 확인한다.

## DB와 초기화

PostgreSQL 컨테이너 하나에 dev/test/perf DB를 분리한다. Redis 키는 goods:dev:, goods:test:, goods:perf:다.
Flyway V1/V2는 각 DB에 첫 연결할 때 적용한다. Hibernate는 validate만 한다.
Mock PG receipt는 해당 DB에 있으므로 DB 초기화와 함께 초기화된다.

```powershell
.\ops\reset-db.ps1 -Environment test
# dev 데이터 삭제 의도를 명시할 때만:
.\ops\reset-db.ps1 -Environment dev -AllowDevReset
```

스크립트는 이 프로젝트의 API/Worker/Mock PG를 먼저 멈추고, 대상 DB 확인 후 명시된 테이블만 초기화한다.
Flyway 이력, PostgreSQL 볼륨, 다른 프로젝트는 보존한다. 실행 뒤 서비스는 중단 상태다.
perf DB 최초 마이그레이션은 docker compose --env-file ops/perf.env up -d --wait --wait-timeout 240으로 기동하여 적용한다.
그 뒤 측정 전 reset-db.ps1 -Environment perf를 실행하고 같은 env-file로 다시 기동한다.
측정 결과를 먼저 저장하고 부하 발생기를 끝낸 뒤 초기화한다.

## 문서

읽기 전용 준비 확인: `pwsh -NoProfile -File ./ops/performance.ps1`.
perf 초기화·기동과 단일 실험 기록은 같은 스크립트의 `Prepare` / `Run`으로 분리했다.
`Run`은 RPS를 명시해야 하며 실제 부하를 발생시킨다. 자세한 사용법은 아래 가이드를 따른다.

- [비즈니스 계약](PROJECT.md), [인프라·트랜잭션 설계](DESIGN.md)
- [FastAPI 경험에서 Spring 코드 읽기](docs/learning.md)
- [자원 예산·성능 목표·검증 범위](docs/performance.md)
- [Java 성능 실험 결과와 분석](artifacts/performance/README.md)
- [Git Bash에서 기능 확인과 향후 부하테스트 준비](docs/load-test-guide.md)
