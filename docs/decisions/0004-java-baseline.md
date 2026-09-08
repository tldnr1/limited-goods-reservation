# ADR 0004: Java 기준 구현과 제한된 Redis admission

상태: 승인 (사용자의 Java 새 구현 전환 요청).

Python 코드와 당시 미커밋 실험 자료를 archive/python-fastapi-baseline에 커밋하고 main을 그 이력 위에서 Java로 전환한다.
archive/java-spring-v3.2는 그대로 둔다. Gradle Wrapper만 가져오고 비즈니스 코드는 새로 작성한다.

ADR 0001의 Python 실행 기술 선택은 이 결정으로 대체한다. 기능 중심 모놀리스라는 방향은 유지한다.
ADR 0002의 PostgreSQL 기준 상태, ADR 0003의 주문과 결제 시도 분리는 유지한다.
sale_items에 재고 카운터를 함께 두고, 일관된 순서의 비관적 락으로 원자적 점유와 인당 제한을 보호한다.

결제 접수는 영속적인 CREATED를 저장한 뒤 202를 반환한다.
별도 Worker가 lease와 PG 멱등키로 재처리를 보장한다. 브로커는 추가하지 않는다.
Redis는 선택적으로 구매 동시 진입 제한과 1초 부정 캐시를 제공하며 재고 원장을 소유하지 않는다.
따라서 DB 멱등 조회와 hot-row 비용은 남고, 성능 향상 폭은 측정으로 확인해야 한다.

3일은 개발 목표 일정이며 성능 합격을 보장하는 약속은 아니다.
완료 기능과 미측정 실험을 docs/performance.md에서 구분한다.
