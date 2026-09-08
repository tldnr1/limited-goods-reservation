# Limited Goods 작업 지침

현재 main은 Java 21 / Spring Boot 3.5 / PostgreSQL 기반 새 구현이다.
Python 및 당시 미커밋 실험 자료는 archive/python-fastapi-baseline에 보존했다.
archive/java-spring-v3.2는 변경하지 않는다. 과거 구현의 결과를 새 구현의 측정값으로 인용하지 않는다.

먼저 PROJECT.md(계약), DESIGN.md(실행·트랜잭션), docs/learning.md(코드 읽기)를 읽는다.
현재 검증 범위와 미완료 성능 실험은 docs/performance.md에 있다.
공통 실행 진입점은 ops/performance.ps1이다. 기본 Check는 읽기 전용이며,
Prepare는 perf 데이터 초기화, Run은 실제 부하를 포함한다. 부하 실행 요청 없이 Run을 사용하지 않는다.

- 요청한 범위만 변경하고 비즈니스 불변식과 실패 동작을 보존한다.
- DB는 Flyway로 변경한다. Hibernate ddl-auto는 validate를 유지한다.
- DB 정합성 테스트는 실제 PostgreSQL의 limited_goods_test에서 실행한다.
- perf 실행 중 dev/test를 동시에 실행하지 않는다. DB 이름만 나눠도 물리 자원은 공유된다.
- Git 메타데이터나 Docker 접근이 sandbox에서 거부되면 정상적인 권한 상승을 사용한다.
  ACL을 완화하거나 sandbox를 해제하지 않는다. 승인 후 같은 오류가 반복되면 중단하고 보고한다.
- JDK를 찾지 못하면 Windows의 User/Machine JAVA_HOME도 확인한다. 설치된 JDK를 재설치하지 않는다.
