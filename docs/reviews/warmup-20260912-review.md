# 2026-09-12 첫 Warmup 실패: 건수 판정과 Payment 오류 분리

대상: [20260912-202809-675-target-warmup-normal](../../artifacts/performance/20260912-202809-675-target-warmup-normal/config.json).
실행 commit은 `c6cfbc1`, 보관 commit은 `88c3103`이다. 이번 분석은 원본을 읽었으며 실제 부하 재실행이나 PG/Payment/DB 설정 변경은 하지 않았다.

## 건수 판정 오류

[summary](../../artifacts/performance/20260912-202809-675-target-warmup-normal/k6-summary.json)의 실제 시작/완료/구매는 각각 272회, dropped=0이다.
로컬 raw.json의 `target_started` Point를 `data.tags.scenario`별 합산하면 21/50/100/101회로 계획 20/50/100/100보다 2회 많다.
raw.json은 Git 제외 대상이므로 단계별 집계는 로컬 원본 재검산 결과이며, 총 272회는 보관된 summary에서 확인할 수 있다.

기존 review와 cleanup의 고정 270 비교를 수정했다. 도착 수는 시나리오별 경계 추가 1회만 허용하고,
구매·결제·DB 상태는 계획 수 대신 실제 시작 수와 정확히 일치해야 한다. dropped와 Hikari delta=0 계약은 유지한다.
따라서 이번 실행의 도착 수 272 자체는 허용되지만 **실행 전체는 여전히 실패**다.

## 확인된 서비스 오류

| 항목 | 확인값 |
|---|---|
| 결제 클라이언트 결과 | 202=254회, 500=18회, 5초 client timeout=4회 |
| 실제 DB | 주문 272, 결제 시도/성공/확정 258, pending=0, 미확정 점유 14 |
| Hikari boundary delta | Payment=18, Reservation/Worker/Mock PG=0 |
| Payment 연결 | 오류 로그 total=4/active=4/idle=0, Prometheus pending max=6, usage max=5.793초 |
| PG 호출 | 관측 Worker PG timer max=1.295864102초, Mock PG Hikari pending max=0 |
| 정합성/기한 | 최종 inventory/userLimit/hold/duplicateSuccess/successDeadline 위반=0 |
| restart/OOM | 수집 전후 컨테이너 비교에서 없음 |

근거: [k6 log](../../artifacts/performance/20260912-202809-675-target-warmup-normal/k6.log),
[서비스 로그](../../artifacts/performance/20260912-202809-675-target-warmup-normal/services.log),
[boundary after](../../artifacts/performance/20260912-202809-675-target-warmup-normal/boundary-after.json),
[Prometheus](../../artifacts/performance/20260912-202809-675-target-warmup-normal/prometheus.json),
[DB](../../artifacts/performance/20260912-202809-675-target-warmup-normal/after-db.json),
[timeline](../../artifacts/performance/20260912-202809-675-target-warmup-normal/timeline.json).

timeout 로그의 주문 ID 4개는 timeline에서 모두 SUCCEEDED이며 nginx는 최초 요청을 499로 기록했다.
따라서 258-254=4는 응답 유실이며 Worker 미완료 4건이 아니다. 그러나 전체 272건의 성공 연결은 충족하지 못했다.
로컬 raw target_latency의 응답 완료 시각을 measurementStart 기준으로 계산하면 500은 +6.728~10.733초,
client timeout은 +8.629~8.819초에 집중된다. 최초 202 응답은 +11.033초다.

## 원인 판단과 한계

직접 확인된 실패 지점은 **Mock PG가 아니라 Payment API가 DB connection을 얻는 단계**다.
[PaymentService.start](../../src/main/java/com/limitedgoods/payments/PaymentService.java)는 접수를 DB에 저장하며 PG를 동기 호출하지 않는다.
PG 호출은 Worker가 수행한다. 최초 PG receipt는 약 11:30:57Z이며, Payment 오류는 그 이전부터 발생했다.

[실제 컨테이너 설정](../../artifacts/performance/20260912-202809-675-target-warmup-normal/before-containers.json)은
Payment 0.25 CPU/pool 4, Mock PG 0.25 CPU/pool 2, PostgreSQL 1.5 CPU다. MockPgDelayMs=0이다.
[자원 표본](../../artifacts/performance/20260912-202809-675-target-warmup-normal/resources.jsonl)에서
11:30:49Z/11:30:55Z Payment CPU는 24.15%/23.79%로 한 코어의 25% quota에 가깝다.
같은 표본의 Mock PG는 1.26%/11.29%, PostgreSQL은 5.27%/5.18%였다.
Mock PG의 나중 25.23% 표본은 초기 Payment 실패의 직접 원인 증거가 아니다.

**가장 유력한 후보는 Payment의 첫 경로 초기화/JIT 등 CPU 비용과 낮은 CPU quota가 결합한 연결 장기 점유다.**
이는 인과 확정이 아니다. 같은 JVM의 준비 완료와 첫 결제 경로 예열은 다르며,
DB 표본의 lockWaiters=0도 표본 사이의 대기를 배제하지 못한다.
현재 artifact에는 CPU throttling 누적 카운터·thread/JFR profile·세밀한 쿼리/락 대기 시계열이 없어
CPU 제약, 초기화, DB 대기의 기여도를 분리할 수 없다. pool 크기나 Mock PG CPU를 늘리면 해결된다고 단정하지 않는다.

다음 동일 조건 실행에서는 초기 Payment 오류 시각, Hikari usage/pending/delta, CPU, 최초 acceptance와 PG receipt 시각을 비교한다.
같은 양상이 반복되면 재현성을 강화하지만 원인 확정은 아니다. 계속 불명확하면 별도로 승인된 상세 관측이 필요하다.
현재 자원·pool·PG delay·timeout·warmup workload는 그대로 유지한다.
