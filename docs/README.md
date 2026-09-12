# 문서 안내

현재 계약과 실행 절차, 특정 실행의 분석, 포트폴리오 주장을 구분해 관리한다.
명령은 별도 설명이 없으면 저장소 루트에서 실행한다.

| 목적 | 문서 |
|---|---|
| 비즈니스 계약 / 트랜잭션 설계 | [PROJECT](../PROJECT.md), [DESIGN](../DESIGN.md) |
| 현재 성능 목표·자원·검증 상태 | [performance](performance.md) |
| 코드 읽기 | [learning](learning.md) |
| 역할·상태·데이터 구조 | [Target v1](architecture/target-v1.md), [부하 흐름](architecture/target-v1-load-scenario.md), [데이터 관계](architecture/domain-model.md), [구매·결제 상태](architecture/purchase-flow.md) |
| 실행·진단 절차 | [Target 부하 가이드](guides/target-v1-load-guide.md), [baseline 가이드](guides/load-test-guide.md), [baseline 진단](guides/diagnostics.md) |
| 실험 분석 | [분석 목록](reviews/README.md) |
| 포트폴리오 | [발전 과정](portfolio/portfolio-evolution.md), [claim/evidence ledger](portfolio/portfolio-evidence.md) |
| 설계 결정과 변경 이유 | [ADR 목록](decisions/README.md) |

## 현재 검증 상태

- Java baseline의 예열 후 10 RPS/60초 성공과 기존 Worker 병목 관측은 과거 조건의 결과다.
- Target 기능 검증, 과거 cold Worker 실패, 첫 단계형 Warmup 실행까지 evidence가 있다.
- 2026-09-12 Warmup은 실제 272회 도착·완료했지만 Payment Hikari timeout 18건과 결제 오류로 실패했다.
- 고정 270건 판정과 cleanup을 실제 도착 수 기준으로 수정하고 offline 회귀 검사를 통과했다.
  수정 후 동일 조건 실제 재실행과 embedded warmup PASS 이후 steady-state 측정은 아직 대기 중이다.
- CPU·pool·Mock PG delay·timeout은 변경하지 않았다. Payment 초기 CPU 제약은 원인 후보이며 확정된 인과는 아니다.

날짜가 있는 실행 기록과 artifact는 당시 조건을 설명한다. 현재 SLO와 혼동하지 않고,
최신 상태는 이 목차와 performance, 실행 방법은 guides, 주장 가능 범위는 portfolio ledger를 따른다.
