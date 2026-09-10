# Target v1 기능 검증 — 2026-09-10

범위: 구현·자동 테스트·소량 HTTP 기능 확인. k6, 처리량/포화점 측정, 5만 명 시나리오는 실행하지 않았다.

## 결과

- 실제 PostgreSQL `limited_goods_test` / Redis 테스트 namespace의 결과는 [tests.json](tests.json)에 집계했다.
- [HTTP smoke](smoke.json): 판매 `dec8b426-9feb-471b-ab0e-dcd1d6b3aab9`, 4건 모두 CONFIRMED, 최종 sold=4 / held=0 / available=0.
- 미사용 READY를 실제 11초 기다려 EXPIRED 확인. 그 사이 ghost hold=0.
- 정상, PG 응답 유실, 지연 확정, Redis 중단 중 기존 주문 재조회·지연 결제 확정을 확인했다. Redis는 finally에서 복구했다.
- 300초 점유 경계와 299초 결제 접수/만료 경쟁은 실제 DB와 제어 시계 테스트로 검증했다. 실제 5분 대기 시험은 미실행이다.
- 전체 dev 재고 불변식 조회는 위반 0행. [invariants.txt](invariants.txt)의 전체 주문 11건은 기존 데이터도 포함하므로 이번 smoke 처리 건수로 인용하지 않는다.
- 두 Nginx 설정 검사 통과. [실행 역할](health.json), [자원 설정](resources.json), [컨테이너 상태](containers.json) 기록.

## 첫 통합 시도의 문제와 수정

첫 대기 등록이 Public Nginx의 3초 응답 제한에서 504로 중단됐다. [당시 로그](initial-waiting-timeout.txt)에
첫 요청 시점 Redis 연결 재시도가 남았다. Waiting readiness는 당시 readinessState만 확인하여 Redis 접속 준비를 확인하지 않았다.
Waiting의 readiness에 Redis health를 포함한 후 이미지를 재빌드하고 역할 전체를 새로 기동했다.
이후 위 HTTP smoke를 통과했다. 응답 timeout이나 CPU/메모리 상한을 늘려 통과시킨 것이 아니다.

## 해석 범위

Worker의 고정 4건/250ms 제한 제거는 단일 dispatch가 실행 슬롯 수보다 많은 작업을 연속 처리하는 회귀 테스트로 확인했다.
재고 트랜잭션에서는 새 주문 응답을 위한 재조회 3개를 제거하고 락 획득 후 프록시 반환까지의 타이머를 추가했다.
이 변경의 처리량 개선 비율은 아직 측정하지 않았다.

25 tickets/s, READY 50, 정상 약 32/s 및 결제 집중 약 125/s는 여전히 정책/계산에 기반한 초기 목표다.
대량 polling 중 결제 SLO, 실제 PG의 긴 timeout/lease, 재확인과 신규 작업의 처리 예산은 후속 검증 범위다.
