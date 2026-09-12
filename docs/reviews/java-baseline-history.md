# Java baseline 실행 이력 — 2026-09-08~09

아래 내용은 각 실행 당시의 결과와 미완료 범위를 보존한 기록이다. 당시의 '이번', '다음 단계', '미실행'은 현재 상태를 의미하지 않는다.
현재 계약과 진행 상태는 [성능 문서](../performance.md), 이후 Target 실행은 [분석 목록](README.md)을 따른다.

## 2026-09-08 실행 기록

- JDK 21: Gradle test/bootJar 성공. PostgreSQL 계약 테스트 18개 + Redis 테스트 4개, 실패 0.
- 준비된 전체 컨테이너에서 gate 비활성 스모크: SUCCESS/LOST_RESPONSE/DELAYED_SUCCESS 모두 CONFIRMED.
  판매 a5deaf5f-aefe-4657-bc8b-2ece0e5529a0의 최종 sold=3, held=0, available=0.
- 테스트 DB reset: Flyway 이력 2개 보존, 기존 dev 주문 3개 보존 확인.
- k6 inspect 성공. 실제 3,000 RPS 부하 실행 및 성능 합격 판정은 수행하지 않음.
- docker inspect로 표의 CPU/memory 제한 적용, 각 컨테이너 restart=0 / OOMKilled=false 확인.
- 최초 PowerShell 준비 확인은 localhost/2초 요청 제한에서 실패했고 127.0.0.1/5초로 수정 후 통과.
- 최종 빌드 + gate 활성화 + 전체 재기동에서는 Mock PG의 늦은 기동으로 첫 SUCCESS가 30초 안에 확정되지 않아 스모크가 중단됨.
  주문 58253073-1d6a-4451-9e2d-4d363d5a0dbe는 이후 Worker 재확인으로 CONFIRMED/SUCCEEDED가 됨.
  이 실행에서 남은 시나리오와 소진 후 멱등 재조회 검사는 도달하지 않았으므로 통과로 간주하지 않음.
- 마지막 dev DB 검사: CONFIRMED 4개, SUCCEEDED 4개, ops/invariants.sql 위반 0행.

사용자의 반복 오류 시 중단 요청에 따라 기동 준비 조건의 추가 수정/스모크 재실행은 멈췄다.
다음 실행 전 API뿐 아니라 Mock PG의 준비 상태도 기다리는 기동 순서를 검토해야 한다.

## 기동 오류 수정 후 기능 재검증 (2026-09-08)

- Mock PG → API 2개·Worker → Nginx 순서에 Spring readiness / Compose health 조건을 적용했다.
- smoke는 네 앱의 health를 먼저 검사한다. 기동 대기와 결제 처리 대기를 분리했고 결제 확인 30초, 점유/유예 기한은 변경하지 않았다.
- Git Bash에서 test/bootJar 성공: 기능 테스트 22개, 실패 0.
- 앱 컨테이너를 모두 정지한 뒤 Redis gate 활성 상태로 재기동하고, --wait 완료 직후 스모크를 실행했다.
- 판매 20baf25d-11d3-45f2-bead-7e71ce6b78e7: 정상/응답 유실/지연 결제 모두 CONFIRMED,
  sold=3 / held=0 / available=0. 소진 후 신규 구매 거절과 기존 구매 멱등 재조회도 통과했다.
- 위의 기동 시 스모크 중단 문제는 이번 확인에서 재현되지 않았다. 운영 중 PG 장애의 모든 경우를 검증했다는 뜻은 아니다.
- 이번 작업에서는 k6 실행, RPS 증가 실험, 실제 성능 측정을 하지 않았다.

다음 단계 안내는 [Git Bash 부하테스트 가이드](../guides/load-test-guide.md)에 있다.
현재 스크립트의 최초 도착 부하와 아직 미구현인 재시도·재구매 시나리오를 구분하여 읽는다.

## 실행 자동화 준비 확인 (2026-09-09)

ops/performance.ps1의 Check / Prepare / Run으로 공통 절차를 통합했다.
기본 Check는 현재 환경을 변경하지 않는다. Run은 명시한 RPS로 실험 한 번만 수행하도록 작성했다.
PowerShell 문법 검사와 Git Bash에서 Check를 실행해 ready를 확인했다.
PostgreSQL·Redis·앱 4개는 healthy, Nginx는 running이며 Nginx 경유 읽기 API 응답이 정상이다.
이번에는 Prepare/Run, 초기화, 구매·결제 스모크, 기능 테스트 재실행, 부하 측정을 하지 않았다.
따라서 실제 부하 발생부터 결과 수집까지의 자동화 경로는 아직 실행 검증 전이다.
다음 단계는 낮은 고정 요청량 실험 한 번으로 생성기·수집 유효성을 확인하는 것이다.

## 초기 실패 후 진단 보강 (2026-09-09)

첫 baseline은 10 RPS/30초 워밍업에서 결제 접수 500 세 건과 dropped iteration 한 건으로 중단됐다.
본 측정·반복 실행은 하지 않았다. 원래 자료와 분석은
`artifacts/performance/20260909-011321-469-baseline-purchase-spike/review.md`에 보존했다.

구매 단계 계측, 100ms DB 대기 표본, 1초 Prometheus, nginx 요청별 upstream/시간/request_id,
워밍업 실패 자료 보존을 추가했다. 사용법과 해석 한계는 [diagnostics.md](../guides/diagnostics.md)를 따른다.

- PostgreSQL 계약 18개 + Redis 4개 + 진단 성공/실패 경로 3개, 총 25개 테스트와 bootJar 통과.
- 실제 PostgreSQL에서 관측 SQL 두 표본 저장/횟수 제한 종료, 별도 관측 세션 명시적 종료 확인.
- PowerShell 구문, Compose 설정, nginx -t, promtool check config 통과.
- 실제 계약 테스트에서도 단계 로그와 transaction_completion 출력 확인. 이 시간은 perf 측정값으로 사용하지 않는다.
- 이번에는 perf 데이터 초기화, 워밍업, k6 본 측정, 성능 개선 효과 검증을 실행하지 않았다.
  Run의 전체 실패 수집 경로는 다음 승인된 실행에서 확인해야 한다.

## 워밍업 판정과 본 측정 분리 (2026-09-09)

사용자 실행 `20260909-134159-227-baseline-purchase-spike`는 구매/결제 301건 모두 성공·확정,
요청 누락과 Hikari timeout 0이었다. 300/300 고정 판정으로 본 측정 전에 중단된 자동화 오류다.
초기 지연 이후 안정화한 실행으로 해석하며, 현재 자료만으로 비관적 락/풀의 10 RPS 지속 처리 한계를 주장하지 않는다.

고정 판정을 실제 성공 건수와 판매별 상태 대조로 변경했다. 워밍업에는 지연 SLO를 추가하지 않는다.
본 측정은 구매 p99/결제 접수 p95를 별도로 검사한다. 자세한 실행 조건과 결과 파일은 load-test-guide.md를 따른다.
사용자의 최종 실행 범위에 따라 이번 작업은 수정·문서·로컬 커밋까지이며 부하/JFR/Java 테스트를 실행하지 않는다.

## 사용자 본 측정 완료 및 결과 보관 (2026-09-09)

`20260909-204228-733-baseline-purchase-spike`: 워밍업 301건, 본 측정 602건 모두 확정.
본 측정 구매 p99 17.76ms, 결제 접수 p95 9.59ms, HTTP 오류/요청 누락/재고 불변식 위반 0.
세부 근거와 범위는 [실행 분석](../../artifacts/performance/20260909-204228-733-baseline-purchase-spike/review.md)에 있다.
이전 301/301 판정 중단 실행도 실패 원인과 harness 변경 근거로 함께 보존한다.
이번 원격 반영 작업은 기존 결과의 검토·기록이며 추가 부하 실행은 하지 않았다.
