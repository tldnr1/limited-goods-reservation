# 예열 후 10 RPS 본 측정 기준선

- 실행 코드: bcfebec. 사용자 실행, baseline/Gate OFF, Diagnostics ON, JFR 없음.
- API 각 CPU 0.75/768MiB/Hikari 8, Tomcat 40, 비관적 재고 락, random two least_conn 유지.
- run.status=collected, k6 exit=0. 워밍업과 본 측정은 서로 다른 판매다.

## 결과

| 항목 | 워밍업 | 본 측정 |
|---|---:|---:|
| 요청 패턴 | 10 iterations/s × 30초 | 10 iterations/s × 60초 |
| 구매/결제 접수/최종 확정 | 각각 301 | 각각 602 |
| 구매 p95 | 473.58ms | 14.64ms |
| 구매 p99 | 별도 합격 판정 없음 | 17.76ms |
| 구매 최대 | 2376.36ms | 64.68ms |
| 결제 접수 p95 | 별도 합격 판정 없음 | 9.59ms |
| HTTP 오류 / dropped iteration | 0 / 0 | 0 / 0 |

602는 실제 완료 iteration 수다. 구매 성공 뒤 결제 접수가 추가되므로 본 측정 HTTP는
판매 등록 1 + 구매 602 + 결제 접수 602 = 1205건이다. 구매 RPS와 전체 HTTP RPS를 구분한다.

워밍업 Hikari는 API 2개/Worker/Mock PG 모두 timeout 증가 0, 프로세스 시작 시각 유지.
본 측정~수집 종료의 Prometheus API 2개/Worker timeout 카운터도 0이다.
after-db.txt의 903건은 워밍업 301 + 본 측정 602의 합이며, 재고 불변식 위반은 0행이다.
본 측정 판매 e975f357-943c-4518-aac6-60efd803bf10: available 398, held 0, sold 602.
워밍업 판매 f310328e-7139-4616-8a36-b110b660a995: available 699, held 0, sold 301.

구매 p99는 로컬 raw.json의 구매 http_req_duration 표본에서 계산했다.
계산 방식·원시 파일 해시는 measured-metrics.json에 있다. summary.json의 기본 출력에는 p99 숫자가 없다.
summary의 threshold boolean은 이 k6 버전에서 실패 여부를 나타낸다. false는 실패하지 않았다는 뜻이며 exit=0과 대조했다.

## 결론과 범위

예열 후 10 RPS에서 요청 처리와 확정이 안정적이었다. 초기 비용을 10 RPS 지속 처리 한계로 해석하지 않는다.
한 번의 실행이며 3000 RPS 목표, 재시도/포기/반환, PG 장애, 대표 조건 반복 검증은 남아 있다.
DB 대기 표본은 워밍업만 수집하며 본 측정은 HTTP/단계 로그/Prometheus를 사용한다.
전후 변경과 예열 효과를 개별적으로 분리한 성능 개선율을 주장하지 않는다.

다음에는 충분한 재고의 capacity 시나리오를 준비한 뒤 25→50→100 RPS 등 한 단계씩 판단한다.
재고 1000개를 그대로 쓰면 소진 후 거절과 실제 점유 처리 성능이 섞인다.
별도의 business workload에서 원래 재고/수요와 재시도·결제·반환 흐름을 검증한다.
