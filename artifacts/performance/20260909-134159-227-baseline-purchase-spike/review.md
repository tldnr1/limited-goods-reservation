# 워밍업 성공 후 harness 판정 오류

- 실행 코드: 5dca689, baseline/Gate OFF, random two least_conn, Diagnostics ON.
- 워밍업 10 iterations/s × 30초: 실제 구매 301건(201), 결제 접수 301건(202), 오류/누락 0.
- warmup-exit-code.txt는 0. failed-db.txt는 CONFIRMED 301, SUCCEEDED 301, 재고 불변식 위반 0행.
- 판매 c9d27d0c-60e3-4fda-b485-60c995294350: total 1000, available 699, held 0, sold 301.
- 최종 run.status=failed는 서비스 실패가 아니라 performance.ps1의 정확한 300/300 비교 때문이다.
  phases.json에 loadStart가 없으며 본 측정은 실행되지 않았다.

## 관측과 해석

구매 p95 198.59ms, 최대 2688.00ms. 초기 몇 초의 지연 뒤 안정화했다.
nginx 구매 분포 api1/api2=147/154, 결제 접수=146/155. 배정 정책 변경의 개별 인과 효과를 확정하지 않는다.
Prometheus의 API 2개/Worker Hikari timeout 카운터는 모두 0이었다.

초기 api1의 idempotency_lookup 단계가 약 1.29초였으나 대응 시간대 DB 표본은
idle in transaction / ClientRead였다. 이 전체 시간을 DB SELECT 실행 시간으로 해석하지 않는다.
JVM/Hibernate/JDBC first-use와 CPU 제한은 후보이며 세부 기여도는 확정하지 않았다. DB 락 대기도 함께 관측됐다.

후속 수정 bcfebec는 실제 성공 구매 수를 해당 판매의 주문·점유·결제 확정 수와 대조한다.
초기 지연은 예열 자료로 보존하고 응답 SLO는 본 측정에 적용한다.
db-waits-errors.txt의 관측 연결 종료 메시지는 db-waits-status.txt에 기록된 의도적 종료다.
