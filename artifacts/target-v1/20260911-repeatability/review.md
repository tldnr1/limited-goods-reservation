# Target v1 반복 실행 및 생성기 데이터 공유

## 변경

- Prepare는 빌드 후 기존 Target 앱을 중단하고 한 번 기동한다. 데이터를 초기화하지 않는다.
- 기존 Compose의 service_healthy 관계를 유지한다. Mock PG의 Flyway 이후 Reservation, Payment/Worker, Checkout Nginx가 준비된다.
- Run -Reset은 준비 검사 → 앱 중단 → 이전 DB 상태/timeline 보존 → perf 초기화 → 기존 이미지로 한 번 기동 → 준비 검사 → 측정 순서다.
- 반복 실행은 build/pull 없이 수행한다. Check는 계속 읽기 전용이며 Run도 같은 검사를 포함한다.
- reset-db의 AppsStopped는 남은 앱을 검사하고 중복 stop만 생략한다. DB 이름 검증, Flyway 이력·볼륨 보존은 유지한다.
- 저장소 단위 파일 잠금은 Prepare/Run의 drain·수집까지 유지한다. 다른 도구나 checkout의 동시 작업까지 보장하지 않는다.
- Docker 래퍼는 Invoke-Docker로 명명하고 stdout을 내부 변수에 모으지 않는다. 호출자가 필요한 조회 결과만 수집한다.
- 판매 metadata와 주문 목록을 fixture.json / orders.json으로 분리한다. SharedArray에서 주문 목록을 프로세스당 한 번 파싱하며 기존 iteration 인덱스와 사용자/멱등키를 유지한다.
- 앞선 미커밋 보완인 필수 threshold 누락 거절 및 PowerShell 호출 회귀 검사도 포함한다.

## 검증

Git Bash에서 pwsh -NoProfile 및 Node를 호출했다.

| 검사 | 결과 |
|---|---|
| target-command-test.ps1 | 6개 통과: 이름 충돌, 읽기 전용 Check, 전달 인자, 환경·위치 복원, 오류 중단 |
| target-lifecycle-test.ps1 | 13개 통과: 임시 workspace와 Docker/HTTP/Git/build 모의 구현. Prepare 단일 기동·데이터 보존, Run -Reset 무빌드 단일 중단/초기화/기동, snapshot 순서, 실행/수집 잠금, 잘못된 입력 및 단계별 실패 차단, AppsStopped 검사 |
| target-review-test.ps1 | 7개 통과: 필수 threshold 누락 포함 결과 판정 |
| target-static-test.mjs | 15개 통과: 기존 시나리오·재시도·시간 계약 및 두 VU 문맥의 주문 파일 단일 로드·서로 다른 주문 선택·fixture 소진 검사 |
| PowerShell parser | 관련 스크립트 8개 구문 오류 없음 |
| 실제 docker compose config --format json | 병합된 Target 11개 서비스, 16개 health 의존 관계, Target Prometheus 설정 확인. 컨테이너 변경 없음 |
| git diff --check | 통과 |

## 검증 범위

실제 k6, 반복 HTTP, 부하, 300초 대기, 실제 Prepare/Run·DB 초기화·컨테이너 재기동은 실행하지 않았다.
라이프사이클 테스트의 Run은 모의 fixture HTTP 경계에서 종료하며 실제 k6에 도달하지 않는다.
Java 비즈니스 코드·Flyway SQL·READY ticket·300초 hold·Payment/Worker 격리를 변경하지 않았다.
따라서 실제 기동 시간, 메모리 절감량, 최대 처리량 달성의 증거는 아니다. 사용자 실행으로 확인해야 한다.
Run -Reset은 JVM을 재시작하지만 DB/OS 캐시는 남을 수 있으므로 완전한 cold 환경이라고 부르지 않는다.
