# 성능 실험 기준

Java 정합성 테스트·컨테이너 스모크와 예열 후 10 RPS/60초 본 측정 1회를 완료했다. 아래 과거 기록과 구분하며,
전체 실행 요약은 [실험 기록](../artifacts/performance/README.md)을 따른다.
과거 Python 수치는 archive/python-fastapi-baseline에 보존했으며 Java 성능 수치로 재사용하지 않는다.

## 자원 예산

Ryzen 5600은 6코어/12논리 CPU, 호스트 RAM 16GB다. 제공된 Docker 정보는 12 CPU/7.715GiB였다.
Compose cpu quota는 물리 코어 전용 할당이 아니다. localhost 결과를 같은 사양의 EC2 성능으로 환산하지 않는다.

| 서비스 | CPU quota | 메모리 상한 | DB pool |
|---|---:|---:|---:|
| Nginx | 0.25 | 128MiB | — |
| API 각 2개 | 각각 0.75 | 각각 768MiB | 각각 8 |
| PostgreSQL | 1.5 | 1536MiB | — |
| Redis | 0.25 | 256MiB | — |
| Worker | 0.5 | 512MiB | 4 |
| Mock PG | 0.25 | 512MiB | 2 |
| Prometheus (선택) | 0.25 | 256MiB | — |

핵심 서비스 합계 4 CPU/3968MiB, Mock PG 포함 4.25 CPU/4480MiB다.
선택 관측 도구와 부하 발생기는 남은 예산에서 실행하고 실제 CPU/메모리도 기록한다.
JVM heap 상한은 컨테이너 메모리의 65%다. 나머지가 native/thread/direct memory를 모두 보장한다는 뜻은 아니다.
API 하나와 둘을 비교할 때는 API 총 CPU=1.5, 총 메모리=1536MiB, 총 pool=16을 유지해야 한다.
현재 Compose는 2개 구성이다. 단일 API 공정 비교용 설정/측정은 아직 하지 않았다.

## 목표와 판정

| 항목 | 목표 |
|---|---|
| 정합성 | 초과 판매/인당 우회/부분 점유/중복 확정 0건 |
| 최초 점유 응답 | 99% ≤ 1초, 성공·재고 부족·429 별도 분포 |
| 예상 밖 5xx/timeout/network error | 정상 시나리오 ≤ 0.1% |
| 정상 PG + 충분한 수요 | 120초 내 판매 확정 ≥ 950/1000 |
| 결제 접수 | 부하 중 p95 ≤ 1초, PG 완료 시간 별도 |
| 반환 재고 재구매 가능 | 반환 가능 시점부터 ≤ 5초 |
| 복구 | 부하 종료 30초 내 작업/락 대기 안정, 복구 가능한 Worker 작업 30초 내 처리 |
| 자원 | 정상 부하에서 OOM/restart/DB checkout timeout 없음 |
| 반복 | 대표 시나리오 3회 개별 결과, 생성기 dropped_iterations=0 |

429는 오류와 분리해도 반드시 비율을 보고한다. 판매 수량/확정 시간 없이 빠른 거절만으로 통과시키지 않는다.
영구 UNKNOWN과 의도적으로 중단한 PG에는 정상 판매 완료 목표를 적용하지 않으며 상태 정합성을 확인한다.

## 실행 방법

공통 절차는 ops/performance.ps1로 통합했다. 명령과 결과 파일 설명은
[Git Bash 실행 가이드](load-test-guide.md)를 따른다.
현재 purchase-spike는 최초 구매 시도와 성공 점유의 결제 접수만 포함하며,
전체 비즈니스 합격 시험이나 자동 RPS 상승 실험은 아니다.

## 남은 성능 실험

- 시드 고정의 정상 backoff+jitter/최대 5회 과도 재시도, 구매 포기/반환 재고 재구매를 포함한 120초 workload.
- 기준선 vs Redis gate의 실제 처리량·락 대기·DB 쿼리/요청 비용.
- 총 자원 고정 API 1개/2개 비교, 포화점과 결제 접수 지연.
- 컨테이너 강제 종료, Redis/PG 장애 상태에서 재기동 복구 시간 측정.
- 생성기 자체 한계 검증과 대표 조건 3회 반복.

자동 테스트에서 Clock과 lease를 이용한 복구 논리는 검증하지만 실제 컨테이너 장애 시간 측정을 대신하지 않는다.
현재 수치는 목표이며 달성 측정값을 아직 기록하지 않았다.

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

다음 단계 안내는 [Git Bash 부하테스트 가이드](load-test-guide.md)에 있다.
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
워밍업 실패 자료 보존을 추가했다. 사용법과 해석 한계는 [diagnostics.md](diagnostics.md)를 따른다.

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
세부 근거와 범위는 [실행 분석](../artifacts/performance/20260909-204228-733-baseline-purchase-spike/review.md)에 있다.
이전 301/301 판정 중단 실행도 실패 원인과 harness 변경 근거로 함께 보존한다.
이번 원격 반영 작업은 기존 결과의 검토·기록이며 추가 부하 실행은 하지 않았다.
