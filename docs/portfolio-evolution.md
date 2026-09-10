# 한정 굿즈 예약 시스템 — 문제를 좁혀 온 발전 기록

**최종 목표(미달성):** 제한된 로컬 자원에서 최초 구매 시도 50,000건·재고 1,000개를 대상으로, 정상 PG·충분한 구매 수요 조건에서 120초 내 950개 이상 판매 확정, 결제 접수 p95 1초 이내, 예상 밖 오류 0.1% 이하, 정합성 위반 0건을 검증한다. 최초 유입은 0~5초 30,000건·5~15초 15,000건·15~60초 5,000건을 새 시험안으로 두고, 재시도·조회는 별도 집계한다. **우선 완료할 개선은 정상 결제 확정 40건/초 이상과 backlog 안정성을 검증하는 것**이다.

아래는 초기부터 완성된 설계를 따랐다는 주장이 아니라, 실험에서 발견한 문제에 따라 판단 기준을 발전시킨 기록이다. archive 수치는 해당 버전의 결과이며 현재 Java와 성능을 직접 비교하지 않는다. Target v1의 역할 분리·입장권·시간 계약은 구현됐지만 새 성능 목표는 아직 미달성이다.

## 1. 동시성 실패 재현 → 정합성 전략 비교 · 이전 Java v1~v2

- **문제:** 단순 조회·검사·갱신으로 재고 100개에 주문 973건이 생성되는 초과 판매와 재고 수치 불일치를 재현했다.
- **원인·판단:** 동시 요청의 오래된 값을 이용한 갱신이 덮어써지는 문제로 보고, 원자적 UPDATE·비관적 락·Redis Lua를 동일 부하 행렬에서 비교했다.
- **해결·대안·한계:** 세 대안 모두 각 15회 기본 비교에서 초과 판매·재고/주문 불일치 0건을 기록했다. 속도와 단순성을 함께 비교할 기준을 확보했으며, 결제·장애 복구까지 증명한 결과는 아니다. [실패 재현](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v1-oversell-baseline.md) · [전략 비교](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v2-stock-strategy-comparison.md)

## 2. 정상 성능만으로 부족 → 실패 경계와 유입 제어 검토 · 이전 Java v2~v3.2

- **문제:** Redis 차감 후 DB 저장 실패 10건을 주입하자 차감 100건·주문 90건의 불일치가 남았다. 품절·중복 요청도 구매 경로에 부담을 주었다.
- **원인·판단:** Redis 내부의 원자성이 DB 저장까지 보장하지 않음을 확인했다. RDB 단일 트랜잭션과 Redis 보상 처리를 비교하고, 대기실 및 Front Gate로 구매 진입을 제어했다.
- **해결·대안·한계:** v3.1은 입장률·활성 인원 제한의 효과를 탐색했다. v3.2는 RDB 대비 Front Gate의 p95가 43.5~69.5% 낮았고, 24회 비교에서 초과 판매·예약 불일치 0건이었다. 단, 대기실 OFF·조건별 1회인 로컬 VU 시험이며 프로세스 종료 시 Redis/DB 복구는 미완료였다. [장애 주입](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v2-stock-failure-injection.md) · [대기실](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v3-1-entry-control.md) · [Front Gate 비교](https://github.com/tldnr1/limited-goods-reservation/blob/0c39efc77bdc1725f665b726570ec1de5d93eab6/records/experiments/v3-2-architecture-load-comparison.md)

## 3. 요청 지연 → DB 대기와 커넥션 사용 추적 · Python 기준선

- **문제:** 목표 70·80 RPS 수동 시험에서 지연과 요청 누락이 발생해 목표 유입을 유지하지 못했다.
- **원인·판단:** 80 RPS 자료의 최대 lock waiter 28개와 70 RPS 자료의 pool 한도 도달을 확인해, 재고 락 대기가 커넥션 장기 점유로 전파될 가능성을 조사했다.
- **해결·대안·한계:** HTTP 결과를 DB 대기·pool 관측과 연결하는 진단 기준을 남겼다. pool 증설을 정답으로 확정하지 않았으며, 두 실행을 하나의 인과관계로 합치거나 병목 개선 완료를 주장하지 않는다. [수동 진단 기록](https://github.com/tldnr1/limited-goods-reservation/blob/32ece7da6051fc48d2a7759fddaa43be273fa5e2/docs/performance-baseline.md)

## 4. 단일 구매 실험 → 결제·복구 계약과 측정 기준 확립 · 현재 Java

- **문제:** 과거 재고 차감 시험만으로 다중 상품 점유·결제 응답 유실을 검증할 수 없었고, 초기 기동 지연과 고정 건수 판정 오류도 성능 해석을 흐렸다.
- **원인·판단:** PostgreSQL을 상태 기준으로 삼고 주문과 결제 시도를 분리했다. readiness·예열·실제 접수 건수 대조를 도입해 시험 오류와 서비스 한계를 구분했다.
- **해결·대안·한계:** 트랜잭션 점유, DB 작업 lease·PG 멱등 처리·UNKNOWN 보유를 구현했다. 예열 후 10 RPS/60초 602건 모두 확정, 구매 p99 17.76ms·결제 접수 p95 9.59ms를 기록했다. 브로커는 보류했으며, 단일 저부하 성공은 최대 용량이나 전체 장애 복구의 증거가 아니다. [설계 결정](decisions/0004-java-baseline.md) · [기능 검증](performance.md) · [본 측정](../artifacts/performance/20260909-204228-733-baseline-purchase-spike/review.md)

## 5. 빠른 접수 → 판매 확정 처리량의 별도 병목 발견 · 현재 Java

- **문제:** Gate OFF·40건/초·60초 시험에서 2,401건을 접수하고 결제 접수 p95는 약 9.5ms였지만, 결제 backlog가 최대 1,473건까지 증가했다.
- **원인·판단:** 약 90초 시계열의 확정 속도는 약 15.4건/초였다. 코드의 dispatch당 최대 4건·fixed delay 250ms가 만드는 약 16건/초 제출 상한과 일치해, HTTP 접수와 비동기 완료 용량을 구분했다.
- **해결·대안·한계:** backlog·작업 나이·최종 확정을 판정에 포함했다. 검사 시점 확정은 1,424건으로 전체 통과는 아니며, 나머지 전부를 실패로 단정하지 않는다. 실행 상한은 식별했지만 제거 후 처리량과 다음 병목은 아직 미측정이다. [결과](../artifacts/performance/20260909-230854-201-baseline-capacity/capacity-result.json) · [시계열](../artifacts/performance/20260909-230854-201-baseline-capacity/order-progress.txt) · [Worker](../src/main/java/com/limitedgoods/worker/Worker.java)

## 6. Target v1 기능 구현 → 단계별 성능 검증 준비

- **문제:** 순간 구매 유입과 결제 확정을 함께 보호한 반복 검증 및 변경 전후 개선 수치가 아직 없다.
- **원인·판단:** 외부 유입·점유·결제의 처리율과 시간 예산이 다르므로 실행 역할을 분리했다. Worker의 4건/250ms 공급 상한은 코드상 제거했으며, 실제 지속 처리량과 새로운 병목은 측정 전이다.
- **해결·대안·한계:** DB 큐·현재 재고 모델을 유지한 채 Waiting/Reservation/Payment/Worker, READY 입장권, 300초 점유·접수+70초 PG 창을 구현하고 기능 계약을 검증했다. [기능 근거](../artifacts/target-v1/20260910-functional/review.md). 다음은 Worker → Waiting → Reservation → Isolation → 50,000명 Business 순서다. 정상 최소 요구 약32/s·첫 검증 목표40/s와 burst의 backlog 기준을 구분한다. [단계별 harness](target-v1-load-guide.md)는 준비됐으나 실행 미검증이며, 현재 기존 주문 fixture는 공급 기간 최대180초다. 과거 계획의 단일5분 시험은 시간에 맞춘 fixture 보충이 필요하다. 대표 조건3회·개선율은 실제 반복/동일 조건 전후 측정 뒤 기록한다. MQ·unit별 재고와 catalog 최적화는 관측된 병목이 있을 때 검토한다.

---

## 예방 설계 — 관측된 장애 해결 이력과 별도

현재 코드에 반영된 위험 대응을 정리한다. 실제 운영 장애를 겪었거나 발생률을 낮췄다는 주장은 하지 않는다. 근거는 기존 코드·계약 테스트이며, 이번 문서 수정에서 테스트를 재실행하지 않았다.

| 예상한 문제 | 예방을 위한 판단·구현 | 대안·검증 범위·한계 |
|---|---|---|
| 중복 요청·서로 다른 키로 인당 한도 우회 | 멱등키 직렬화와 상품 락 이후 한도 검사로 중복 주문과 동시 한도 초과를 방어했다. | 클라이언트 버튼 제한에 의존하지 않는다. 동시 멱등·인당 제한 테스트가 있으며 실제 인증은 범위 밖이다. |
| 여러 상품을 반대 순서로 잠그는 교착 | 구매·확정·반환 모두 상품 UUID 순서로 잠그고 다중 상품 변경을 같은 트랜잭션에 묶었다. | 반대 순서 요청·부분 실패 롤백을 테스트한다. 모든 종류의 교착이나 락 대기를 없애지는 않는다. |
| PG 성공 후 응답 유실로 중복 결제·잘못된 재고 반환 | 결제 시도를 영속화하고 동일 PG 멱등키로 재확인하며, UNKNOWN에서는 새 시도와 만료 반환을 막았다. | lease 재획득·중복 성공·UNKNOWN 테스트가 있다. Mock PG 기준이며 영구 UNKNOWN의 수동 복구는 후속 범위다. |
| PG 지연이 DB 커넥션을 장시간 점유 | 작업 claim을 커밋한 뒤 PG를 호출하고 결과 반영은 별도 트랜잭션으로 처리했다. | 외부 호출까지 DB 트랜잭션으로 감싸는 방식을 피했다. HTTP 대기 슬롯과 공유 DB 자원 고갈은 별도로 관리해야 한다. |
| Redis 장애·반환 실패가 잘못된 구매나 성공 응답 취소로 전파 | Redis를 재고 원장으로 쓰지 않고 신규 진입은 실패 시 차단하며, permit 정리 실패로 커밋 성공을 뒤집지 않았다. | Redis 실패·permit 만료 테스트가 있다. 구매 가용성을 희생하는 정책이며 lease는 재고 정합성 락이 아니다. |

근거: [구매 트랜잭션](../src/main/java/com/limitedgoods/purchases/PurchaseTransactionService.java) · [재고 변경](../src/main/java/com/limitedgoods/reservations/ReservationService.java) · [결제](../src/main/java/com/limitedgoods/payments/PaymentService.java) · [Worker](../src/main/java/com/limitedgoods/worker/Worker.java) · [Redis Gate](../src/main/java/com/limitedgoods/admission/AdmissionGate.java) · [계약 테스트](../src/test/java/com/limitedgoods/ContractTest.java) · [Redis 테스트](../src/test/java/com/limitedgoods/AdmissionTest.java)
