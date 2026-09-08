# 시스템 개요

현재 실행/트랜잭션 구성은 [DESIGN.md](../../DESIGN.md)에 있다.
API와 Worker는 같은 JAR의 별도 컨테이너다. Nginx는 두 API로 분산한다.
Spring Scheduler 자체는 작업 원장이 아니며 PostgreSQL payment_attempts와 lease가 복구 근거다.

PostgreSQL: sales / sale_items / orders / order_items / reservations / payment_attempts.
mock_pg_receipts는 로컬 PG가 응답 유실 후에도 결과를 유지하기 위한 별도 테이블이다.
Redis는 구매 permit과 짧은 재고 소진 캐시만 보관한다.

Mock PG를 포함한 모든 컨테이너가 같은 호스트와 DB 자원을 공유한다.
API 2개 실험은 호스트 장애 내성이나 실제 다중 AZ 가용성을 증명하지 않는다.
