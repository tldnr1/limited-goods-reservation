# Worker 10/s 실행 실패 분석

대상: `artifacts/performance/20260911-234502-053-target-worker-normal`.
입력은 Worker 10/s, 30초, VUs 10, MaxVUs 50, Run -Reset이다. 추가 부하는 실행하지 않았다.
원본 결과와 원래 result.json은 변경하지 않았다.

## 확인된 사실

| 항목 | 결과 / 근거 |
|---|---|
| 실행 | 288 iterations 완료, dropped 13. 계획 유입을 달성하지 못함 / k6-summary.json, raw.json |
| 결제 HTTP | 202 응답 228건, 500 응답 60건, 클라이언트 5초 timeout 4건 / raw.json, k6.log |
| 결제 지연 | 전체 시도 p95 2032ms, 기준 1000ms 초과 / k6-summary.json |
| Payment 연결 풀 | timeout counter 0→60, 로그에 total=4, active=4, idle=0 / prometheus.json, services.log |
| 원장 | attempts=232, succeeded=232, confirmed=232, pending=0, failed=0 / after-db.json |
| 클라이언트/DB 차이 | timeout 주문 4개가 timeline에서 모두 CONFIRMED. Nginx는 해당 최초 요청을 499로 기록 |
| 재고 | fixture 301개 중 sold=232, held=69. 재고·hold·인당 제한·중복 성공 위반은 모든 복원 표본 및 최종값에서 0 |
| 재시작/OOM | 수집 전후 서비스 컨테이너에 없음 |
| 관측 | Payment up=0인 표본이 있음. 원래 JSON 분석 중단으로 뒤쪽 관측 검사까지 도달하지 못했음 |
| 생성기 | 수집된 3개 표본에서 약 19~20MiB, CPU 약 2.4%. 이 표본만으로 순간 부하 전체를 배제할 수 없음 |

500 응답은 측정 시작 후 약 3.5~9.7초에 집중된다. dropped 13건은 약 1~3.4초에 발생했다.
초기 응답 지연으로 기존 VU들이 점유되고 추가 VU가 준비되는 동안 유입이 누락된 양상이다.
MaxVUs=50은 사전 준비 VU=50을 뜻하지 않는다. 생성기 CPU/메모리 포화로 확정할 근거는 없다.

응답 완료 시각 기준 시작+10초 이후의 성공 응답 207건은 p95 약189ms다.
이는 사후 구간 분석이며 실패 구간을 제외해 이번 시험을 통과 처리하는 근거가 아니다.
Payment는 0.25 CPU이고 초기 Docker 표본 약23%는 그 한도에 가까운 사용량이다.
기동 직후 코드 초기화/JIT 등의 CPU 비용이 후보지만 프로파일·세밀한 DB 대기 자료 없이 원인으로 확정할 수 없다.
연결 풀 고갈은 확인된 현상이며, 풀 크기 부족이 근본 원인이라는 뜻은 아니다.
Worker는 받은 작업을 모두 완료했다. Payment 공급 실패가 있으므로 Worker의 10/s 한계나 40/s 달성을 판단할 수 없다.

## 수정 및 재분석

- PostgreSQL json_agg 결과의 물리적 줄바꿈을 그대로 JSONL로 저장한 버그를 수정했다.
  이제 한 표본 전체를 파싱·압축 직렬화한 뒤 한 줄로 저장한다. 잘못된 JSON이면 관측 준비를 통과하지 못한다.
- 자동 판정의 논리와 수치는 유지했다. HTTP/DB 건수 불일치와 pending/failed 잔존 메시지만 분리했다.
  `232 > 228`은 미완료 작업이 있다는 뜻이 아니므로 기존 메시지는 이 사례를 잘못 설명했다.
- 이 과거 파일에 한해 최상위 `{"at"` 시작 경계로 8개 JSON 객체를 복원했다.
  각 객체의 inventory 2개를 확인했으며 `db-samples.normalized.jsonl`에 별도로 저장했다.
  원본 경로와 SHA-256은 source.json에 기록했다.
- 임시 복사본에서 복원 표본과 수정한 review를 사용한 결과는 result-reanalysis.json이다.
  JSON 분석 중단은 해소됐지만 threshold·유입·HTTP/DB 대조·Hikari·scrape 검사 실패가 유지된다.
  capacity 값은 일부 공급 구간 표본의 기울기이며 지속 처리 용량의 합격값이 아니다.
- Git Bash → pwsh에서 observer 8개(실제 자식 프로세스, 모의 Docker), review 8개 검사 통과.
  실제 Docker/HTTP/k6·DB 초기화·서비스 재시작·부하·대기 시험은 실행하지 않았다.

## 사용자 결정이 필요한 다음 단계

현재 실험은 매번 JVM 재기동 직후 부하를 시작한다. 이를 유지해 초기 지연 원인을 진단할지,
별도 예열 후 지속 처리량을 측정하는 실험을 추가할지 결정해야 한다.
포트폴리오의 Worker 지속 처리량 확인에는 후자를 별도 결과로 구분하는 방식을 제안한다.
단순 시간 대기는 결제 경로 예열의 보장이 아니다. 예열 모드를 추가할 경우 입력·완료 확인·측정 제외 경계가 필요하다.
사용자 결정 전에는 예열 추가, VU 기본값, CPU/풀 크기, timeout, SLO 및 재시도 정책을 변경하지 않는다.
