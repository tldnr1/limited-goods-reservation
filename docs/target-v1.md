# Target v1 — 첫 기능 구현

이번 실행 결과: [2026-09-10 기능 검증](../artifacts/target-v1/20260910-functional/review.md).

목적은 대량 대기와 DB 점유·결제 경로를 분리하고, 300초 점유 및 기한 내 결제 접수를 안전하게 처리하는 것이다.
이 문서는 완성된 성능 명세가 아니다. 기존 archive/baseline 수치를 새 구현의 성능으로 인용하지 않는다.

## 이번 구현

- 같은 JAR의 `waiting` / `reservation` / `payment` / `worker` profile. Waiting은 DataSource/JPA/Flyway가 없다.
- Redis는 WAITING/READY와 짧은 판매 상태 projection만 소유한다. PostgreSQL이 주문·점유·재고·결제 원장이다.
- 대기 등록만으로 점유하지 않는다. READY를 받은 클라이언트가 명시적으로 구매 POST를 전송한다.
- Worker는 기본 동시성 4를 유지하고 작업 완료 즉시 다음 DB 작업을 가져온다. 빈 큐만 250ms 대기한다.
- 현재 상품 행 락 모델과 인당 제한·다중 상품 원자성을 유지한다. 새 주문 응답 재조회 3개를 임계 구간에서 제거한다.
- MQ, 재고 단위 행 전환, 실제 PG, 브라우저 UI, 대규모 부하는 포함하지 않는다.

## 상태·시간 계약

| 항목 | v1 계약 / 초기값 |
|---|---|
| 판매 전 | 등록 거절. DB 판매 시각을 Redis에 투영해 검사하고 실제 구매에서도 DB 검사 |
| 대기 | WAITING → READY → 클라이언트가 점유 요청. 서버 자동 점유 없음 |
| 대기 기한 | 등록부터 최대 180초. 정상 polling이 30초 동안 없으면 대기 순서에서 제외 |
| READY | 발급부터 최대 10초, 전체 대기 기한을 넘지 않음. 조회로 연장하지 않음 |
| 초기 제한 | READY 발급 25/s, 미사용 READY 50개, 대기+READY 50,000개. 모두 정책/후속 측정 대상 |
| 점유 진입 | 별도 25/s 및 기존 전체 permit 8. 측정으로 조정할 초기 제한 |
| 재접수 | 만료 후 같은 사용자·멱등키·본문으로 다시 등록 가능. 활성 등록 중 다른 본문은 409 |
| 점유 | 생성 시점부터 300초. 실제 DB 락·커넥션은 트랜잭션 종료 시 반환 |
| 결제 접수 | 주문 락 획득 후 기한 검사. 정확히 만료 시각이면 거절. 기한 내 DB 접수 후에는 만료 작업이 반환하지 않음 |
| PG 최초 처리 창 | 접수+70초. Mock PG의 임시 정책이며 처리 지연 SLO나 실제 토스 계약이 아님 |
| UNKNOWN | 같은 시도로 재확인. 시간 경과만으로 재고를 반환하거나 새 결제를 허용하지 않음 |

READY는 재고 보장이나 엄격한 FIFO가 아니다. 접수 순서의 선두 후보 중 실제 조회하는 브라우저에게 발급한다.
입장권은 사용자·구매 멱등키·본문 fingerprint·기한에 서명한다. 기한이 지난 입장권도 서명/소유권이 맞고
이미 DB 주문이 있으면 그 주문을 반환한다. 없는 주문의 신규 생성은 거절한다.
재고 락 대기 중 READY가 만료되면 DB 변경 없이 거절한다. DB 성공 후 Redis 후처리 실패는 성공을 뒤집지 않는다.

Redis 유실 시 미점유 순서 복구는 보장하지 않는다. 기존 주문의 조회·결제는 Redis 없이 동작한다.
점유 직후 이탈한 사용자의 300초 보유는 정상적인 포기로 남는다. 이 구현은 늦은 서버 자동 점유를 제거한다.
모두 HELD인 경우 반환 대기이며 SOLD_OUT과 구분한다. 최대 180초 대기는 300초 반환을 보장하지 않는다.

## 역할·자원

`compose.yaml`은 기존 baseline, `compose.target.yml`은 Target 오버레이다. 같은 Compose 프로젝트/DB 볼륨을 사용한다.
동시에 실행하지 않는다. 별도 저장소나 데이터베이스로 분리하지 않는다.

| 역할 | 개수 | 각 CPU quota | 각 메모리 상한 | 각 DB pool |
|---|---:|---:|---:|---:|
| Waiting (api1/api2) | 2 | 0.375 | 384MiB | 없음 |
| Reservation | 1 | 0.5 | 512MiB | 4 |
| Payment | 1 | 0.25 | 384MiB | 4 |
| Worker | 1 | 0.5 | 512MiB | 4 |
| Mock PG | 1 | 0.25 | 384MiB | 2 |
| Public / Checkout Nginx | 2 | 0.125 | 64MiB | 없음 |
| PostgreSQL | 1 | 1.5 | 1536MiB | — |
| Redis | 1 | 0.25 | 256MiB | — |

총 4.25 CPU / 4,480MiB로 기존 총상한을 유지한 최초 배분이다. 물리 자원 전용 할당이나 처리량 보장이 아니다.
Waiting HTTP/Redis와 Reservation/Payment의 Tomcat/Hikari가 분리되지만, DB·호스트·네트워크는 공유한다.
대기 포트는 8080, 구매/결제 포트는 8082다. 모두 localhost 전용이다. X-User-Id와 기본 서명 비밀은 로컬 시험용이다.

## API와 실행

1. 8082 `POST /api/sales`: 기존 로컬 fixture 생성. Reservation의 주기적 projection이 Redis에 반영될 때까지 잠깐 503 가능.
2. 8080 `POST /api/admissions`: 기존 구매 본문, `X-User-Id`, `Idempotency-Key`. 202와 id 반환.
3. 8080 `GET /api/admissions/{id}`: 같은 사용자. READY 응답의 ticket을 보관한다.
4. 8082 `POST /api/purchases`: 같은 본문/멱등키와 `X-Admission-Ticket`. 응답 유실 시 같은 요청으로 재시도한다.
5. 8082 `POST /api/orders/{id}/payments`, `GET /api/orders/{id}`: 기존 결제/조회 계약.

조회 응답은 `Cache-Control: no-store`, `Retry-After`/retryAfter를 제공한다. 가까운 대기는 1초, 먼 대기와 반환 대기는 12초다.
클라이언트는 이 최소 간격에 양의 jitter를 추가하고 완료 시 중단해야 한다. 브라우저 UI는 미구현이다.
대기 항목이 TTL로 사라지거나 유실되면 GET은 404다. 이를 주문 실패나 판매 완료로 해석하지 않고 재접수/기존 주문 확인으로 처리한다.
너무 이른 조회는 새 READY 발급 판단을 생략한다. 이는 HTTP ingress 자체의 rate limit을 대체하지 않는다.

```powershell
# 기본은 읽기 전용 상태 확인
./ops/target.ps1 -Action Check
./ops/target.ps1 -Action Start
./ops/target.ps1 -Action Smoke -RedisOutage
./ops/target.ps1 -Action Stop
```

Start는 JAR/image를 빌드하고 dev 역할 구성을 기동한다. 데이터 초기화/볼륨 삭제는 하지 않는다.
Smoke는 작은 dev 판매를 새로 만들며, RedisOutage를 지정하면 Redis를 잠시 중단 후 복구한다.
기존 perf 실행은 Target Stop 후 `ops/performance.ps1`을 사용한다. 아직 Target 전용 대량 harness는 없다.

## 검증과 다음 단계

자동 테스트: 실제 limited_goods_test와 Redis에서 300초 경계, 늦은 결제·만료 경쟁, PG 응답 유실,
만료 입장권/Redis 장애 후 DB 재조회, READY 상한·소유권·미사용 만료·이탈 제외,
Waiting의 DB 없는 기동, Worker 연속 공급·동시성 상한을 확인한다.

남은 일: 300초 실제 시간 보유 시험, 25/s와 정상 약 32/s·집중 약 125/s 실측, 5만 명 통합 부하,
앞단 폭주 중 결제 SLO, HTTP ingress 제한/브라우저 jitter, 신규 작업과 재확인 처리 예산의 세분화.
현재 catalog publisher는 전체 판매를 1초 주기로 한 번 조회한다. 이벤트 수가 커질 때는 활성 판매 범위/페이징을 도입해야 한다.
projection은 5초 TTL이며 장애 시 신규 대기는 503으로 닫힌다. 반영 지연과 조회 간격 때문에 UI의 반환 인지는 더 늦을 수 있다.
현재 2초 Mock PG timeout과 10초 lease를 실제 PG에 그대로 적용하면 안 된다. 긴 호출의 lease 갱신·소유권 검증은 후속이다.
